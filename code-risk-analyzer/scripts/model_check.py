"""Check that every model the routing can reach still answers, before a long run finds out.

Models change almost weekly; config/ask/models.json (or ~/.config/code-risk/models.json) names the ones in use. This
sends each ACTIVE profile the routing policy uses one tiny structured request at its lowest listed effort (a few hundred
tokens each) and reports which tiers still have a working route. Forbidden models and efforts are refused before any
call. Nothing is written to a project; changing models stays a one-line edit of models.json that every PC pulls.

    ask --check-models
"""
import importlib.util
from pathlib import Path
import sys
import tempfile

SCRIPTS = Path(__file__).resolve().parent
PROBE_TIMEOUT = 120
PROBE_SCHEMA = {'type': 'object', 'properties': {'status': {'type': 'string', 'enum': ['COMPLETED']}},
                'required': ['status'], 'additionalProperties': False}
PROBE_PROMPT = 'Connectivity check. Reply with the JSON object {"status": "COMPLETED"} and nothing else.'


def _module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _probe_valid(value):
    if not isinstance(value, dict) or value != {'status': 'COMPLETED'}:
        raise ValueError('Connectivity probe expects {"status": "COMPLETED"}')
    return value


def check(routing, call):
    """{'models': {profile id: result}, 'tiers': {tier: {...}}}; raises ValueError for invalid or forbidden settings."""
    ask, router = _module('ask'), _module('model_router')
    registry = router.validate_registry(ask._read(routing['registry']))
    policy = router.validate_policy(ask._read(routing['policy']), registry)
    ask.models({'routing': {'registry': routing['registry'], 'policy': routing['policy']}})  # every tier and fallback
    used = sorted({route['modelId'] for tier in policy['tiers'].values() for route in [tier, *tier.get('fallbacks', [])]})
    results = {}
    for profile_id in used:
        profile = registry['models'][profile_id]
        if profile['status'] != 'ACTIVE':
            results[profile_id] = {'model': profile['model'], 'ok': False, 'reason': f"status {profile['status']}"}
            continue
        effort = next(level for level in profile['reasoningEfforts'] if level not in ask.FORBIDDEN_EFFORTS)
        config = {'provider': profile['adapter'], 'model': profile['model'], 'allowedModels': [profile['model']],
                  'reasoningEffort': effort, 'timeoutSeconds': PROBE_TIMEOUT}
        with tempfile.TemporaryDirectory(prefix='code-risk-model-check-') as workspace:
            outcome = call(config, str(Path(workspace).resolve()), PROBE_PROMPT, PROBE_SCHEMA, _probe_valid)
        ok = outcome.get('status') == 'COMPLETED'
        results[profile_id] = {'model': profile['model'], 'provider': profile['adapter'], 'ok': ok,
                               'reason': '' if ok else f"{outcome.get('status')} {outcome.get('failureKind', '')}: {outcome.get('reason', '')}".strip(),
                               'usage': outcome.get('usage', {})}
    tiers = {}
    for tier, primary in policy['tiers'].items():
        chain = [primary, *primary.get('fallbacks', [])]
        working = [route for route in chain if results[route['modelId']]['ok']]
        tiers[tier] = {'primary': results[primary['modelId']]['ok'], 'working': len(working), 'routes': len(chain)}
    return {'models': results, 'tiers': tiers}


def report(value, registry_path):
    lines = ['모델 점검:']
    for profile_id, result in value['models'].items():
        lines.append(f"- {profile_id} ({result['model']}): " + ('정상' if result['ok'] else '실패 - ' + result['reason']))
    for tier, state in value['tiers'].items():
        if not state['working']:
            lines.append(f'- {tier}: 쓸 수 있는 모델이 없습니다. 이 Tier를 쓰는 단계는 실패합니다')
        elif not state['primary']:
            lines.append(f"- {tier}: 첫 모델이 실패해 fallback으로만 동작합니다 ({state['working']}/{state['routes']})")
    if any(not result['ok'] for result in value['models'].values()):
        lines.append(f'모델을 바꾸려면 {registry_path}의 model 값을 고치세요. 저장소 파일이면 push 후 모든 PC가 다음 ask 때 받습니다. '
                     '인증 실패면 해당 CLI에 다시 로그인하세요')
    else:
        lines.append('모든 모델이 응답했습니다')
    return '\n'.join(lines)


def benchmark_report(routing):
    """The routes the benchmark numbers give, and whether the routing files in use still match them."""
    select, ask = _module('model_select'), _module('ask')
    path = select.CONFIG/'model-benchmarks.json'
    if not path.is_file():
        return ''
    value = select.validate(ask._read(str(path)))
    registry, policy = select.derive(value)
    lines = [select.describe(value)]
    if ask._read(routing['registry']) != registry or ask._read(routing['policy']) != policy:
        lines.append('사용 중인 models.json·routing-policy.json이 벤치마크에서 나온 경로와 다릅니다. '
                     '의도한 덮어쓰기가 아니면 python3 scripts/model_select.py --write 후 push하세요')
    return '\n'.join(lines)


def main(call=None, routing=None):
    """0: every model answered, 1: at least one failed, 2: invalid or forbidden settings."""
    ask = _module('ask')
    routing = routing or ask.default_routing()
    try:
        value = check(routing, call or _module('agent_runner').run)
    except (ValueError, OSError, KeyError) as error:
        print(f'모델 설정 거부: {error}')
        return 2
    print(report(value, routing['registry']))
    try:
        extra = benchmark_report(routing)
    except (ValueError, OSError, KeyError) as error:
        extra = f'벤치마크 파일 거부: {error}'
    if extra:
        print(extra)
    return 0 if all(result['ok'] for result in value['models'].values()) else 1


if __name__ == '__main__':
    sys.exit(main())
