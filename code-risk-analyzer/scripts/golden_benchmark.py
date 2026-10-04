"""Small Golden Benchmark: fixed Java bug-fix tasks with hidden checks, run against model routes before a manual switch.

Each task in scripts/eval/golden/<name>/ has task.json (title, description, file), the buggy source and Check.java.
The model sees the description and the buggy file only, answers with the whole fixed file, and the hidden check runs
with `java Check.java` (Java 22+ multi-file source launcher). The result is pass rate, tokens and time per route; it does
not change models.json. Compare the current route with a candidate, then edit model-benchmarks.json if the numbers
justify it and regenerate the routing with model_select.py.

    python3 scripts/golden_benchmark.py --routes general/medium,review/high --repeat 2 --output build/golden.json
    ask --benchmark-models     # every route the routing policy uses, once each
"""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

SCRIPTS = Path(__file__).resolve().parent
TASKS = SCRIPTS/'eval'/'golden'
CHECK_TIMEOUT = 120
SCHEMA = {'type': 'object', 'properties': {'content': {'type': 'string'}}, 'required': ['content'], 'additionalProperties': False}


def _module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def tasks(root=TASKS):
    found = []
    for folder in sorted(path for path in root.iterdir() if (path/'task.json').is_file()):
        value = json.loads((folder/'task.json').read_text(encoding='utf-8'))
        if set(value) != {'title', 'description', 'file'} or not (folder/value['file']).is_file() or not (folder/'Check.java').is_file():
            raise ValueError(f'Invalid golden task {folder.name}')
        found.append(dict(value, name=folder.name, folder=folder))
    if not found:
        raise ValueError('No golden tasks')
    return found


def prompt(task):
    source = (task['folder']/task['file']).read_text(encoding='utf-8')
    return (f"Fix this Java class. {task['description']}\n"
            f"Keep the class name, public API and imports usable with the JDK only. Answer with the complete fixed file "
            f"as JSON {{\"content\": \"...\"}}.\n\n--- {task['file']} ---\n{source}")


def _content(value):
    if not isinstance(value, dict) or set(value) != {'content'} or not isinstance(value['content'], str) or not value['content'].strip():
        raise ValueError('Fixed file content required')
    return value


def check(task, content, run=subprocess.run):
    """True when the hidden check passes against the model's file."""
    with tempfile.TemporaryDirectory(prefix='code-risk-golden-') as workspace:
        folder = Path(workspace)
        (folder/task['file']).write_text(content, encoding='utf-8')
        shutil.copy(task['folder']/'Check.java', folder/'Check.java')
        try:
            result = run(['java', 'Check.java'], cwd=folder, capture_output=True, timeout=CHECK_TIMEOUT)
        except subprocess.TimeoutExpired:
            return False
        return result.returncode == 0


def route_config(registry, route):
    """'general/medium' -> agent config for that registered profile and effort; forbidden models are refused."""
    ask = _module('ask')
    profile_id, _, effort = route.partition('/')
    profile = registry['models'].get(profile_id)
    if profile is None or effort not in profile['reasoningEfforts']:
        raise ValueError(f'Unknown route {route}: use <profile>/<effort> from models.json')
    if any(word in profile['model'].lower() for word in ask.FORBIDDEN_MODELS) or effort in ask.FORBIDDEN_EFFORTS:
        raise ValueError(f'Forbidden model or effort: {route}')
    return {'provider': profile['adapter'], 'model': profile['model'], 'allowedModels': [profile['model']],
            'reasoningEffort': effort, 'timeoutSeconds': 600}


def benchmark(routes, registry, call, repeat=1, run=subprocess.run, root=TASKS):
    results = {}
    for route in routes:
        config = route_config(registry, route)
        attempts = []
        for task in tasks(root):
            for attempt in range(repeat):
                started = time.monotonic()
                with tempfile.TemporaryDirectory(prefix='code-risk-golden-agent-') as workspace:
                    outcome = call(config, str(Path(workspace).resolve()), prompt(task), SCHEMA, _content)
                passed = outcome.get('status') == 'COMPLETED' and check(task, outcome['output']['content'], run)
                attempts.append({'task': task['name'], 'attempt': attempt + 1, 'passed': passed,
                                 'status': outcome.get('status'), 'seconds': round(time.monotonic() - started, 1),
                                 'usage': outcome.get('usage', {})})
        passed = sum(item['passed'] for item in attempts)
        tokens = {}
        for item in attempts:
            for key, value in item['usage'].items():
                if isinstance(value, (int, float)):
                    tokens[key] = tokens.get(key, 0) + value
        results[route] = {'model': config['model'], 'effort': config['reasoningEffort'], 'passed': passed,
                          'total': len(attempts), 'passRate': round(passed / len(attempts), 3), 'usage': tokens,
                          'seconds': round(sum(item['seconds'] for item in attempts), 1), 'attempts': attempts}
    return results


def policy_routes(policy):
    """Distinct <profile>/<effort> routes in the policy, primaries and fallbacks, in tier order."""
    routes = []
    for tier in sorted(policy['tiers']):
        route = policy['tiers'][tier]
        for step in [route, *route.get('fallbacks', [])]:
            name = step['modelId'] + '/' + step['reasoningEffort']
            if name not in routes:
                routes.append(name)
    return routes


def report(results):
    lines = ['Golden Benchmark:']
    for route, value in results.items():
        failed = sorted({item['task'] for item in value['attempts'] if not item['passed']})
        lines.append(f"- {route} ({value['model']}/{value['effort']}): {value['passed']}/{value['total']} 통과, "
                     f"{value['seconds']}초, 토큰 {value['usage'] or '기록 없음'}" + (f", 실패: {', '.join(failed)}" if failed else ''))
    return '\n'.join(lines)


def main(argv=None, call=None):
    parser = argparse.ArgumentParser(description='Run the golden bug-fix tasks against model routes')
    parser.add_argument('--routes', help='comma-separated <profile>/<effort>, e.g. general/medium,review/high '
                                         '(default: every route the routing policy uses)')
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--output')
    args = parser.parse_args(argv)
    ask = _module('ask')
    try:
        if not 1 <= args.repeat <= 5:
            raise ValueError('--repeat must be between 1 and 5')
        routing = ask.default_routing()
        registry = _module('model_router').validate_registry(ask._read(routing['registry']))
        routes = [route.strip() for route in args.routes.split(',') if route.strip()] if args.routes \
            else policy_routes(ask._read(routing['policy']))
        print(f'{len(routes)}개 경로 × 과제 {len(tasks())}개 × {args.repeat}회 = 모델 호출 {len(routes) * len(tasks()) * args.repeat}번 '
              '(과제당 짧은 파일 하나, 구독 CLI 사용량으로 처리)')
        results = benchmark(routes, registry, call or _module('agent_runner').run, args.repeat)
    except (ValueError, OSError, KeyError) as error:
        print(f'벤치마크 거부: {error}')
        return 2
    print(report(results))
    if args.output:
        Path(args.output).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    return 0


if __name__ == '__main__':
    sys.exit(main())
