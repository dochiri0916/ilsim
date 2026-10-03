"""One natural-language entry point: picks the task, the CODE_CHANGE stages and the model tier, then runs them.

Classification is keyword based and calls no model, so planning costs no tokens. Expensive models and the max
reasoning effort are refused for every route a session could reach, including fallbacks.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

SCRIPTS = Path(__file__).resolve().parent
WORKFLOW = SCRIPTS/'code_change_workflow.py'
DEFAULT_JAR = SCRIPTS.parent/'build'/'libs'/'code-risk-analyzer-0.0.1-SNAPSHOT.jar'
SESSION_CONFIG = 'ask-config.json'

FORBIDDEN_MODELS = ('astra', 'fable', 'opus')
FORBIDDEN_EFFORTS = ('max',)

# Checked in order; the first match wins. A change verb outranks status and analysis words.
INTENTS = (
    ('PULL_REQUEST', ('pr', 'pull request', '풀리퀘', '제출')),
    ('CODE_CHANGE', ('추가', '구현', '수정', '고쳐', '고치', '변경', '바꿔', '바꾸', '만들', '개발', '리팩터', '삭제', '제거',
                     'fix', 'add', 'implement*', 'change', 'refactor*')),
    ('STATUS', ('어디까지', '진행 상황', '진행상황', '현재 상태', 'status')),
    ('COST', ('비용', '토큰', '사용량', 'cost', 'token')),
    ('RULES', ('규칙', 'rule')),
    ('MUTATION', ('변이', 'mutation')),
    ('VERIFY', ('검증', 'verif*')),
    ('CONTINUE', ('이어서', '이어', '계속', '다음 단계', 'continue', 'resume')),
    ('ANALYZE', ('분석', '점검', '스캔', '진단', 'scan', 'analy*')),
)
# Same areas the Java ChangeRouter treats as high risk; anything else is a TIER_2 change. TIER_4 is never chosen.
RISK = ('결제', '금액', '환불', '정산', '돈', '재고', '상태 전이', '상태전이', '동시', '트랜잭션', '롤백', '재시도', '메시지',
        '이벤트', '멱등', '중복', '락', '보안', '권한', '인증', '개인정보', '테넌트', 'tenant', 'sql', 'payment', 'stock',
        'inventory', 'concurren*', 'transaction', 'rollback', 'retr*', 'idempot*', 'security', 'auth*', 'lock', 'message')

ORDER = ('baseline', 'generate-tests', 'generate-implementation', 'verification', 'local-service', 'review', 'complete', 'pull-request')
NEXT = {None: 0, 'BASELINED': 1, 'TEST_WRITING': 2, 'IMPLEMENTATION': 3, 'VERIFIED': 4, 'REVIEWED': 6, 'COMPLETED': 7}
# Each request runs up to the next point where a person has to look: verification, review, PR.
STOPS = ('verification', 'review', 'pull-request')
STAGE_OPTIONS = {'verification': ('approvals',), 'repair': ('approvals',), 'local-service': ('container',),
                 'review': ('bundle', 'open-evidence', 'service', 'fixture-policy'), 'pull-request': ('pull-request',)}
REQUIRED_OPTIONS = {'review': ('bundle', 'open-evidence'), 'pull-request': ('pull-request',)}
READ_ONLY = ('STATUS', 'COST', 'RULES', 'MUTATION', 'ANALYZE')


def _matches(text, words):
    found = []
    for word in words:
        # ASCII words match whole words; a trailing * marks a stem such as concurren*.
        stem = word.rstrip('*')
        pattern = re.escape(stem)
        if word.isascii():
            pattern = r'(?<![a-z])' + pattern + ('' if word.endswith('*') else r's?(?![a-z])')
        if re.search(pattern, text):
            found.append(stem)
    return found


def classify(text):
    lowered = text.lower()
    for intent, words in INTENTS:
        found = _matches(lowered, words)
        if found:
            return intent, 'keyword: ' + ', '.join(found)
    raise ValueError('요청을 이해하지 못했습니다. 예: "주문 취소에 중복 방지 추가해줘", "위험 분석해줘", "이어서 진행해", "PR 올려줘"')


def tier(text):
    found = _matches(text.lower(), RISK)
    if found:
        return 'TIER_3', '고위험 영역: ' + ', '.join(found)
    return 'TIER_2', '고위험 영역 단어 없음'


def stages(intent, phase, generator, service):
    if phase not in NEXT:
        raise ValueError(f'세션 상태 {phase}: 자동 진행할 수 없습니다. state.json의 invalidations를 확인하고 새 세션을 시작하세요')
    start = NEXT[phase]
    if intent == 'CODE_CHANGE' and start > ORDER.index('verification'):
        raise ValueError('이 세션은 검증 이후 단계입니다. 새 변경은 --state로 새 세션 경로를 지정하세요')
    if intent == 'VERIFY':
        if phase not in ('IMPLEMENTATION', 'VERIFIED', 'REVIEWED', 'COMPLETED'):
            raise ValueError('검증할 구현이 없습니다. 먼저 변경 요청을 실행하세요')
        return ['verification']
    if intent == 'PULL_REQUEST':
        if start < ORDER.index('local-service'):
            raise ValueError('PR은 검증을 통과한 세션에서만 만들 수 있습니다')
        stop = len(ORDER) - 1
    else:
        stop = next(ORDER.index(name) for name in STOPS if ORDER.index(name) >= start)
    result = [name for name in ORDER[start:stop + 1] if service or name != 'local-service']
    if not generator and any(name.startswith('generate-') for name in result):
        result = result[:[name.startswith('generate-') for name in result].index(True)]
    return result


def _read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def models(config):
    """Every model and effort the session could call, refusing forbidden ones."""
    routes = []
    if 'agent' in config:
        agent = config['agent']
        routes = [(model, agent.get('reasoningEffort')) for model in sorted(set(agent.get('allowedModels', [])) | {agent.get('model')} - {None})]
    elif 'routing' in config:
        routing = config['routing']
        registry, policy = _read(routing['registry']), _read(routing['policy'])
        tiers = routing['requiredTiers'].values() if 'requiredTiers' in routing else policy['tiers'].keys()
        for name in sorted(set(tiers)):
            route = policy['tiers'].get(name)
            if route is None:
                raise ValueError(f'{name} 경로가 policy에 없습니다')
            for step in [route, *route.get('fallbacks', [])]:
                routes.append((registry['models'][step['modelId']]['model'], step['reasoningEffort']))
    for model, effort in routes:
        if any(word in model.lower() for word in FORBIDDEN_MODELS):
            raise ValueError(f'금지된 고비용 모델이 경로에 있습니다: {model}')
        if effort in FORBIDDEN_EFFORTS:
            raise ValueError(f'reasoningEffort {effort}는 자동 실행에서 금지됩니다 ({model})')
    return sorted({model for model, _ in routes})


def plan(text, config=None, state=None, project=None, allowed_models=None):
    intent, reason = classify(text)
    result = {'request': text, 'intent': intent, 'reason': reason, 'changesCode': intent not in READ_ONLY,
              'state': str(Path(state).absolute()) if state else None, 'stages': [], 'commands': []}
    loaded = _read(config) if config else None
    state_path = Path(result['state']) if state else None
    phase = None
    if state_path and state_path.exists():
        phase = _read(state_path).get('phase')
    result['phase'] = phase
    jar = loaded['analyzer'] if loaded else str(DEFAULT_JAR)
    target = project or (_read(loaded['plan'])['project'] if loaded else None)
    if intent == 'STATUS':
        if not state_path or not state_path.exists():
            raise ValueError('--state로 확인할 세션을 지정하세요')
        return result
    if intent in ('ANALYZE', 'MUTATION'):
        if not target:
            raise ValueError('--project 또는 --config로 대상 프로젝트를 지정하세요')
        result['commands'] = [['java', '-jar', jar, 'scan' if intent == 'ANALYZE' else 'mutation', target]]
        return result
    if intent == 'RULES':
        result['commands'] = [['java', '-jar', jar, 'rules', *re.findall(r'[A-Z]+-\d{3}', text)[:1]]]
        return result
    if intent == 'COST':
        if not state_path:
            raise ValueError('--state로 비용을 볼 세션을 지정하세요')
        result['commands'] = [[sys.executable, str(SCRIPTS/'cost_report.py'), str(state_path.parent)]]
        return result
    if not loaded or not state_path:
        raise ValueError('코드 변경에는 --config와 --state가 필요합니다')
    saved = state_path.parent/SESSION_CONFIG
    if saved.exists():
        loaded, result['configSource'] = _read(saved), 'session'
    elif phase is None and 'routing' in loaded and not {'requiredTiers', 'capability'} & set(loaded['routing']):
        chosen, why = tier(text)
        loaded = dict(loaded, routing=dict(loaded['routing'], requiredTiers={'test-writing': chosen, 'implementation': chosen}))
        result['configSource'], result['tierReason'] = 'auto', why
    else:
        result['configSource'] = 'given'
    result['config'] = loaded
    result['configPath'] = str(saved) if result['configSource'] != 'given' else str(Path(config).absolute())
    result['models'] = models(loaded)
    if allowed_models is not None and not set(result['models']) <= set(allowed_models):
        raise ValueError('허용 목록 밖 모델: ' + ', '.join(sorted(set(result['models']) - set(allowed_models))))
    generator = 'agent' in loaded or 'routing' in loaded
    service = 'fixtureAdapter' in loaded or 'serviceAdapter' in loaded
    result['stages'] = stages(intent, phase, generator, service)
    return result


def describe(value):
    lines = [f"요청: {value['request']}", f"판단: {value['intent']} ({value['reason']})"]
    if value.get('phase') is not None or value['intent'] == 'STATUS':
        lines.append(f"현재 단계: {value.get('phase')}")
    if value['intent'] == 'STATUS':
        try:
            lines.append('다음 실행: ' + ' → '.join(stages('CONTINUE', value['phase'], True, True)))
        except ValueError as error:
            lines.append(str(error))
    routing = value.get('config', {}).get('routing', {})
    if 'requiredTiers' in routing:
        lines.append('모델 Tier: ' + ', '.join(f'{k}={v}' for k, v in routing['requiredTiers'].items())
                     + (f" ({value['tierReason']})" if value.get('tierReason') else ''))
    elif 'capability' in routing:
        lines.append('모델 Tier: JEV 판단')
    if value.get('models'):
        lines.append('사용 가능 모델: ' + ', '.join(value['models']))
    if value['stages']:
        lines.append('실행 단계: ' + ' → '.join(value['stages']))
    for command in value['commands']:
        lines.append('실행: ' + ' '.join(command))
    return '\n'.join(lines)


def execute(value, confirmed, run, options=None):
    options = options or {}
    print(describe(value))
    if not value['changesCode']:
        code = 0
        for command in value['commands']:
            code = run(command)
            if code:
                return code
        return code
    if not value['stages']:
        print('실행할 단계가 없습니다. 생성 단계는 config에 agent 또는 routing이 필요합니다')
        return 3
    if not confirmed:
        print('코드를 바꾸는 단계라 실행하지 않았습니다. 위 계획대로 실행하려면 --yes를 붙이세요')
        return 0
    state = Path(value['state'])
    if value['configSource'] == 'auto':
        state.parent.mkdir(parents=True, exist_ok=True)
        (state.parent/SESSION_CONFIG).write_text(json.dumps(value['config'], ensure_ascii=False, indent=2), encoding='utf-8')
    for stage in value['stages']:
        missing = [name for name in REQUIRED_OPTIONS.get(stage, ()) if name not in options]
        if missing:
            print(f"{stage} 단계에는 {', '.join('--' + name for name in missing)} 파일이 필요해 여기서 멈춥니다")
            return 3
        code = run(_command(stage, value, options))
        if code == 1 and stage == 'verification' and 'repair' in value['config']:
            print('검증 실패: 설정된 repair 루프를 실행합니다')
            code = run(_command('repair', value, options))
        if code:
            print(f'{stage} 단계가 종료 코드 {code}로 멈췄습니다 (1 실패, 2 거부, 3 검토 필요)')
            return code
    return 0


def _command(stage, value, options):
    command = [sys.executable, str(WORKFLOW), stage, '--config', value['configPath'], '--state', value['state']]
    for name in STAGE_OPTIONS.get(stage, ()):
        if name in options:
            command += ['--' + name, options[name]]
    return command


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('request', help='자연어 요청')
    parser.add_argument('--config')
    parser.add_argument('--state')
    parser.add_argument('--project')
    parser.add_argument('--yes', action='store_true', help='계획을 확인했으니 코드 변경 단계를 실행')
    parser.add_argument('--allowed-models', help='허용할 모델 이름 목록(쉼표 구분)')
    for name in ('approvals', 'container', 'bundle', 'open-evidence', 'service', 'fixture-policy', 'pull-request'):
        parser.add_argument('--' + name)
    args = parser.parse_args(argv)
    options = {name: getattr(args, name.replace('-', '_')) for name in
               ('approvals', 'container', 'bundle', 'open-evidence', 'service', 'fixture-policy', 'pull-request')
               if getattr(args, name.replace('-', '_')) is not None}
    allowed = args.allowed_models.split(',') if args.allowed_models else None
    try:
        value = plan(args.request, args.config, args.state, args.project, allowed)
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f'요청 거부: {error}')
        return 2
    return execute(value, args.yes, lambda command: subprocess.run(command).returncode, options)


if __name__ == '__main__':
    raise SystemExit(main())
