"""Do a task in the current folder with Claude Code or Codex, whichever the plan limits favor, without opening either.

    ai 결제 동시성 버그 고쳐줘  the chosen CLI works headless here and prints its progress and answer
    ai 테스트도 추가해줘        continues this folder's last conversation (same CLI, within CONTINUE_WITHIN)
    ai --new ...              start a new conversation
    ai claude | codex ...     use that one
    ai --why                  only print the choice, the reason and the model setting

Claude runs with --permission-mode acceptEdits and only CLAUDE_TOOLS commands; Codex runs in its workspace-write
sandbox. Every task first fast-forwards this tool's checkout (self_update.py; ASK_NO_UPDATE=1 turns it off).
MCP servers stay off (`ai --mcp ...` keeps them) and the agent is asked for short answers, to save
tokens. Hitting a limit mid-task hands the same task to the other CLI.

After a task that changed files, build-convention (./gradlew check without editing the build) and code-risk-analyzer
(scan of the changed src/main files) run on the project. With TYPESAFE_API_KEY, JEV reviews the findings the
static rules could not confirm (at most JEV_MAX_CALLS calls unless .code-risk.yml sets jev.max-calls); its opinion is
advice only and never changes the result. "컨벤션 없이", "위험 분석 빼고" or
"검사 없이" in the request switches that check off for that command only.

Reads the CLIs' own local logs (usage_report.py); no network, no tokens. Codex records its 5-hour and weekly limit
percentages with reset times. Claude Code records no percentages, so its state is estimated: a recorded "limit reached"
message blocks it until its reset, and its 5-hour window is taken to start at the first message after the last window.

Rules, in order:
1. A CLI that hit its limit (or has less than BLOCKED_BELOW percent left) and has not reset yet is skipped.
2. Capacity that resets within EXPIRING_MINUTES is used first: unused, it is lost at the reset.
3. Codex running ahead of its weekly pace is spared for Claude; so is Codex with little 5-hour room left.
4. Otherwise Codex, whose remaining room is known.

Model and reasoning: capacity about to reset with plenty left (BOOST_LEFT percent or more) gets the TIER_4 route's
strongest setting for the chosen CLI; otherwise a task sentence gets tier() (TIER_3 for risk words such as payment,
concurrency or transaction, else TIER_2). Without either the CLI's own default is used. Routes come from
config/models/routing-policy.json, so forbidden models and efforts are never chosen.
"""
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

SCRIPTS = Path(__file__).resolve().parent
WINDOW = timedelta(hours=5)
WEEK = timedelta(days=7)
BLOCKED_BELOW = 3        # percent left that counts as used up
EXPIRING_MINUTES = 30    # reset this close: spend what is left
EXPIRING_MINIMUM = 5     # percent left worth spending before the reset
FIVE_HOUR_ROOM = 20      # Codex 5-hour percent left below which Claude goes first
WEEKLY_PACE_MARGIN = 10  # Codex weekly percent used above the elapsed share of the week
BOOST_LEFT = 30          # percent left, resetting soon, that is worth spending on the strongest setting
EFFORT_ORDER = ('minimal', 'low', 'medium', 'high', 'xhigh')


def _module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


usage = _module('usage_report')


# --- project, routing and key helpers ---
DEFAULT_JAR = SCRIPTS.parent/'build'/'libs'/'code-risk-analyzer-0.0.1-SNAPSHOT.jar'
DEFAULT_ROUTING = SCRIPTS.parent/'config'/'models'
ROUTING_FILES = {'registry': 'models.json', 'policy': 'routing-policy.json'}
FORBIDDEN_MODELS = ('astra', 'fable', 'opus')
FORBIDDEN_EFFORTS = ('max',)
# Request words that mark a high-risk change; they raise the task to TIER_3.
RISK = ('결제', '금액', '환불', '정산', '돈', '재고', '상태 전이', '상태전이', '동시', '트랜잭션', '롤백', '재시도', '메시지',
        '이벤트', '멱등', '중복', '락', '보안', '권한', '인증', '개인정보', '테넌트', 'tenant', 'sql', 'payment', 'stock',
        'inventory', 'concurren*', 'transaction', 'rollback', 'retr*', 'idempot*', 'security', 'auth*', 'lock', 'message')
CONVENTION_OFF = re.compile(r'(컨벤션|코드\s*스타일|스타일\s*검사)\s*(검사|검증|확인)?\s*(없이|빼고|끄고|무시|안\s*지키|하지\s*말|말고)'
                            r'|no[-\s]?convention', re.I)
KEY = 'TYPESAFE_API_KEY'
KEY_LINE = re.compile(r'\s*(?:export\s+)?' + KEY + r'\s*=\s*(.*?)\s*')


def _read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


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


def tier(text):
    found = _matches(text.lower(), RISK)
    if found:
        return 'TIER_3', '고위험 영역: ' + ', '.join(found)
    return 'TIER_2', '고위험 영역 단어 없음'


def find_project(start):
    """The nearest folder at or above start that holds a Gradle wrapper and build file."""
    for folder in [start, *start.parents]:
        if (folder/'gradlew').is_file() and any((folder/name).is_file() for name in
                                                ('settings.gradle', 'settings.gradle.kts', 'build.gradle', 'build.gradle.kts')):
            return folder
    raise ValueError('Gradle 프로젝트 폴더(gradlew가 있는 곳)에서 실행하거나 --project로 지정하세요')


def default_routing(environ=os.environ):
    """config/models in this checkout, each file replaceable from ~/.config/code-risk (or CODE_RISK_CONFIG_HOME)."""
    home = Path(environ.get('CODE_RISK_CONFIG_HOME') or '~/.config/code-risk').expanduser()
    return {key: str((home/name if (home/name).is_file() else DEFAULT_ROUTING/name).resolve()) for key, name in ROUTING_FILES.items()}


def check_routes(routing):
    """Every model and effort the routing policy can reach; refuses forbidden ones before anything runs."""
    registry, policy = _read(routing['registry']), _read(routing['policy'])
    for route in policy['tiers'].values():
        for step in [route, *route.get('fallbacks', [])]:
            model, effort = registry['models'][step['modelId']]['model'], step['reasoningEffort']
            if any(word in model.lower() for word in FORBIDDEN_MODELS):
                raise ValueError(f'금지된 고비용 모델이 경로에 있습니다: {model}')
            if effort in FORBIDDEN_EFFORTS:
                raise ValueError(f'reasoningEffort {effort}는 자동 실행에서 금지됩니다 ({model})')
    return registry, policy


def load_key(environ=os.environ):
    """Fill TYPESAFE_API_KEY from ~/.secrets (or CODE_RISK_SECRETS) when the shell did not export it; never printed."""
    if environ.get(KEY, '').strip():
        return True
    path = Path(environ.get('CODE_RISK_SECRETS') or '~/.secrets').expanduser()
    files = [path] if path.is_file() else sorted(item for item in path.iterdir() if item.is_file()) if path.is_dir() else []
    for file in files:
        if file.stat().st_size > 65536:
            continue
        text = file.read_text(encoding='utf-8', errors='replace')
        if file.name == KEY and '=' not in text:
            values = [text.strip()]
        else:
            values = [match.group(1) for match in map(KEY_LINE.fullmatch, text.splitlines()) if match]
        for value in values:
            value = value.strip().strip('"\'')
            if value:
                environ[KEY] = value
                return True
    return False
LIMIT_TEXT = re.compile(r'(usage limit reached|limit reached|limit will reset|rate limit)', re.I)
EPOCH = re.compile(r'\|(\d{10})\b')
CLOCK = re.compile(r'resets?\s+(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?', re.I)


def _text(message):
    content = message.get('content') if isinstance(message, dict) else None
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return ' '.join(part.get('text', '') for part in content if isinstance(part, dict) and isinstance(part.get('text'), str))
    return ''


def _clock_reset(match, moment):
    hour, minute, half = int(match.group(1)), int(match.group(2) or 0), (match.group(3) or '').lower()
    if half == 'pm' and hour < 12:
        hour += 12
    if half == 'am' and hour == 12:
        hour = 0
    if not 0 <= hour < 24 or not 0 <= minute < 60:
        return None
    local = moment.astimezone()
    reset = local.replace(hour=hour, minute=minute, second=0, microsecond=0)
    if reset <= local:
        reset += timedelta(days=1)
    return reset.astimezone(timezone.utc)


def claude_state(root, now):
    """{'blockedUntil', 'windowResetsAt'} estimated from Claude Code transcripts of the last week."""
    blocked, first = None, None
    files = sorted(Path(root).glob('projects/**/*.jsonl')) if Path(root).is_dir() else []
    moments = []
    for path in files:
        try:
            if datetime.fromtimestamp(path.stat().st_mtime, timezone.utc) < now - WEEK:
                continue
        except OSError:
            continue
        for entry in usage._lines(path):
            if not isinstance(entry, dict) or entry.get('type') != 'assistant':
                continue
            moment = usage._time(entry.get('timestamp'))
            if moment is None or moment < now - WEEK:
                continue
            text = _text(entry.get('message'))
            if (entry.get('isApiErrorMessage') or entry.get('error')) and LIMIT_TEXT.search(text):
                epoch, clock = EPOCH.search(text), CLOCK.search(text)
                reset = (datetime.fromtimestamp(int(epoch.group(1)), timezone.utc) if epoch
                         else _clock_reset(clock, moment) if clock else moment + WINDOW)
                if reset and reset > now and (blocked is None or reset > blocked):
                    blocked = reset
                continue
            if isinstance((entry.get('message') or {}).get('usage'), dict):
                moments.append(moment)
    # Windows start at the first message after the previous window ended.
    for moment in sorted(moments):
        if first is None or moment >= first + WINDOW:
            first = moment
    window = first + WINDOW if first is not None and first + WINDOW > now else None
    return {'blockedUntil': blocked, 'windowResetsAt': window}


def _left(limit, now):
    """(percent left, reset time or None) of a Codex limit; a limit past its reset counts as full."""
    if not limit:
        return None, None
    resets = datetime.fromisoformat(limit['resetsAt']) if 'resetsAt' in limit else None
    if resets is not None and resets <= now:
        return 100.0, None
    return max(0.0, 100.0 - limit['usedPercent']), resets


def _minutes(moment, now):
    return int((moment - now).total_seconds() // 60)


def choose(claude, codex_limits, now, installed=('claude', 'codex')):
    """(name, reason, boost) for the CLI to start; boost: plenty of capacity is about to reset unused."""
    name, why = _choose(claude, codex_limits, now, installed)
    five, _ = _left((codex_limits or {}).get('fiveHour'), now)
    boost = '버려지기 전에' in why and (name == 'claude' or (five is not None and five >= BOOST_LEFT))
    return name, why, boost


def _choose(claude, codex_limits, now, installed):
    five, five_reset = _left((codex_limits or {}).get('fiveHour'), now)
    week, week_reset = _left((codex_limits or {}).get('weekly'), now)
    codex_ok = 'codex' in installed and not any(left is not None and left < BLOCKED_BELOW for left in (five, week))
    claude_ok = 'claude' in installed and claude['blockedUntil'] is None
    if not codex_ok and not claude_ok:
        waits = [moment for moment in (claude['blockedUntil'], five_reset if five is not None and five < BLOCKED_BELOW else None,
                                       week_reset if week is not None and week < BLOCKED_BELOW else None) if moment]
        soonest = min(waits) if waits else None
        return None, '둘 다 한도에 걸렸습니다' + (f' ({_minutes(soonest, now)}분 뒤 먼저 풀림)' if soonest else '')
    if not codex_ok:
        return 'claude', 'Codex 한도 소진' if 'codex' in installed else 'Codex CLI 없음'
    if not claude_ok:
        return 'codex', (f"Claude 한도 도달, {_minutes(claude['blockedUntil'], now)}분 뒤 해제" if 'claude' in installed
                         else 'Claude CLI 없음')
    expiring = []
    if five is not None and five_reset and five >= EXPIRING_MINIMUM and _minutes(five_reset, now) <= EXPIRING_MINUTES:
        expiring.append((five_reset, 'codex', f'Codex 5시간 한도 {five:.0f}% 남음, {_minutes(five_reset, now)}분 뒤 초기화'))
    if claude['windowResetsAt'] and _minutes(claude['windowResetsAt'], now) <= EXPIRING_MINUTES:
        expiring.append((claude['windowResetsAt'], 'claude',
                         f"Claude 5시간 창이 약 {_minutes(claude['windowResetsAt'], now)}분 뒤 초기화(추정)"))
    if expiring:
        _, name, why = min(expiring)
        return name, why + ' → 버려지기 전에 사용'
    if week is not None and week_reset:
        elapsed = 1 - (week_reset - now) / WEEK
        if 100 - week > elapsed * 100 + WEEKLY_PACE_MARGIN:
            return 'claude', f'Codex 주간 한도를 일정보다 빨리 쓰는 중({100 - week:.0f}% 사용, 주간 {elapsed * 100:.0f}% 경과)'
    if five is not None and five < FIVE_HOUR_ROOM:
        return 'claude', f'Codex 5시간 한도 {five:.0f}% 남음'
    room = f'5시간 {five:.0f}%' if five is not None else '5시간 기록 없음'
    return 'codex', f'Codex 여유({room}' + (f', 주간 {week:.0f}%' if week is not None else '') + ' 남음)'


def launch_setting(name, tier, routing):
    """(model, effort) for the chosen CLI from the tier's route, strongest setting of that provider; (None, None) for defaults."""
    if tier is None:
        return None, None
    registry, policy = check_routes(routing)
    route = policy['tiers'].get(tier)
    if route is None:
        return None, None
    steps = [step for step in [route, *route.get('fallbacks', [])] if registry['models'][step['modelId']]['adapter'] == name]
    if not steps:
        return None, None
    step = max(steps, key=lambda item: EFFORT_ORDER.index(item['reasoningEffort']) if item['reasoningEffort'] in EFFORT_ORDER else -1)
    return registry['models'][step['modelId']]['model'], step['reasoningEffort']


# Claude edits files without asking and may run only these commands; Codex writes inside its workspace sandbox.
CLAUDE_TOOLS = ['Read', 'Edit', 'Write', 'MultiEdit', 'Glob', 'Grep', 'TodoWrite', 'Bash(./gradlew *)', 'Bash(gradle *)',
                'Bash(git status*)', 'Bash(git diff*)', 'Bash(git log*)', 'Bash(git show*)', 'Bash(ls*)', 'Bash(cat *)']
# Answers cost output tokens: ask for the result only. MCP servers add their tool definitions to every request.
CONCISE = ('답변은 핵심만 짧게: 바꾼 것과 사람이 확인할 것만 쓴다. 과정 설명, 코드 전문, 반복 요약은 쓰지 않는다.')
NO_MCP_CLAUDE = ['--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}']
NO_MCP_CODEX = ['-c', 'mcp_servers={}']
LIMIT_ERROR = re.compile(r'usage limit|rate limit|limit reached|quota|too many requests|\b429\b', re.I)
CONTINUE_WITHIN = timedelta(hours=3)
USAGE = ('사용법: 프로젝트 폴더에서\n'
         '  ai 회원 탈퇴 API 추가해줘      Claude·Codex 중 한도가 유리한 쪽이 이 폴더에서 작업하고 진행·결과만 보여 줌\n'
         '  ai 방금 거 테스트도 추가해줘    같은 폴더의 이전 대화를 이어서 (3시간 이내, 같은 CLI일 때)\n'
         '  ai --new ...                  새 대화로 시작\n'
         '  ai claude ... / ai codex ...  직접 지정\n'
         '  ai --why                      지금 무엇을 왜 고르는지만 보기')


def command(name, model, effort, resume, mcp=False):
    """The headless command; the task goes to stdin so no option can swallow it. MCP stays off unless asked for."""
    if name == 'claude':
        result = ['claude', '-p', '--output-format', 'stream-json', '--verbose', '--permission-mode', 'acceptEdits',
                  '--append-system-prompt', CONCISE, *([] if mcp else NO_MCP_CLAUDE), '--allowedTools', *CLAUDE_TOOLS]
        result += ['--model', model] if model else []
        result += ['--effort', effort] if effort else []
        return result + (['--resume', resume] if resume else [])
    result = ['codex', 'exec'] + (['resume', resume] if resume else [])
    result += ['--json', '--skip-git-repo-check', '--sandbox', 'workspace-write', *([] if mcp else NO_MCP_CODEX)]
    result += ['-m', model] if model else []
    result += ['-c', f'model_reasoning_effort="{effort}"'] if effort else []
    return result + ['-']


def _short(tool, data):
    data = data if isinstance(data, dict) else {}
    target = data.get('file_path') or data.get('command') or data.get('pattern') or data.get('path') or ''
    return f'  · {tool} {str(target)[:160]}'.rstrip()


EDIT_TOOLS = ('Edit', 'Write', 'MultiEdit', 'NotebookEdit')


def show(name, event, say, edits=None):
    """Print one CLI event as a short line; returns (session id or None, error text or None)."""
    kind = event.get('type')
    if name == 'claude':
        if kind == 'system' and event.get('subtype') == 'init':
            return event.get('session_id'), None
        if kind == 'assistant':
            for part in (event.get('message') or {}).get('content') or []:
                if not isinstance(part, dict):
                    continue
                if part.get('type') == 'text' and str(part.get('text', '')).strip():
                    say(part['text'].strip())
                elif part.get('type') == 'tool_use':
                    say(_short(part.get('name', 'tool'), part.get('input')))
                    target = (part.get('input') or {}).get('file_path') if isinstance(part.get('input'), dict) else None
                    if edits is not None and part.get('name') in EDIT_TOOLS and isinstance(target, str):
                        edits.append(target)
        if kind == 'result':
            error = str(event.get('result') or event.get('subtype') or 'failed') if event.get('is_error') else None
            return event.get('session_id'), error
        return None, None
    if kind == 'thread.started':
        return event.get('thread_id'), None
    item = event.get('item') if isinstance(event.get('item'), dict) else {}
    if kind == 'item.completed':
        if item.get('type') == 'agent_message' and str(item.get('text', '')).strip():
            say(item['text'].strip())
        elif item.get('type') == 'command_execution':
            say(_short('명령', {'command': item.get('command')}))
        elif item.get('type') == 'file_change':
            for change in item.get('changes') or []:
                if isinstance(change, dict):
                    say(_short('파일', {'path': change.get('path')}))
                    if edits is not None and isinstance(change.get('path'), str):
                        edits.append(change['path'])
    if kind in ('turn.failed', 'error'):
        error = event.get('error') if isinstance(event.get('error'), dict) else {}
        return None, str(error.get('message') or event.get('message') or kind)
    return None, None


def run(name, argv, task, cwd, say, popen=subprocess.Popen):
    """{'session', 'error', 'limited'}: streams the CLI's JSON events as short lines."""
    session, error, edits = None, None, []
    try:
        process = popen(argv, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                        encoding='utf-8', errors='replace')
    except OSError as failure:
        return {'session': None, 'error': str(failure), 'limited': False, 'edits': []}
    process.stdin.write(task)
    process.stdin.close()
    for line in process.stdout:
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if isinstance(event, dict):
            found, failed = show(name, event, say, edits)
            session, error = found or session, failed or error
    stderr = process.stderr.read() if process.stderr else ''
    code = process.wait()
    if code and not error:
        error = (stderr.strip().splitlines() or [f'exit {code}'])[-1]
    return {'session': session, 'error': error, 'limited': bool(error and LIMIT_ERROR.search(error + ' ' + stderr)), 'edits': edits}


# Checks run after every task that changed files; a request sentence can switch one off for that command only.
RISK_OFF = re.compile(r'(위험|리스크|코드)\s*(분석|검사|스캔)\s*(없이|빼고|끄고|무시|하지\s*말|말고)|code-?risk\s*(없이|빼고|off)'
                      r'|no[-\s]?(risk|scan)', re.I)
ALL_OFF = re.compile(r'(검사|검증|체크)\s*(는|를|은)?\s*(전부|다|모두)?\s*(없이|빼고|끄고|하지\s*말|말고)|no[-\s]?checks?', re.I)
NAMED_CHECK = re.compile(r'(위험|리스크|코드|컨벤션|스타일|style|risk)\s*$', re.I)
REPORT_DIR = Path('build')/'ai'
# JEV reviews what the static rules could not confirm; each call is a TypeSafe request, capped unless the project sets one.
JEV_MAX_CALLS = 20
SEVERITY_ORDER = ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO')


def check_plan(task):
    """Which checks run for this command: both unless the sentence switches one (or all) off."""
    # "검사 없이" alone switches every check off; "리스크 검사 없이" names one check, handled by its own pattern.
    everything_off = any(not NAMED_CHECK.search(task[max(0, match.start() - 10):match.start()]) for match in ALL_OFF.finditer(task))
    return {'convention': not everything_off and not CONVENTION_OFF.search(task),
            'risk': not everything_off and not RISK_OFF.search(task)}


def changed_sources(project, edits):
    """Project-relative src/main Java files the task edited."""
    result = []
    for path in edits:
        target = Path(path)
        target = target if target.is_absolute() else project/target
        try:
            relative = target.resolve().relative_to(project).as_posix()
        except ValueError:
            continue
        if relative.startswith('src/main/') and relative.endswith('.java') and relative not in result:
            result.append(relative)
    return result


def _jev(item):
    """JEV's advisory note for a finding, or ''."""
    for evidence in item.get('evidence') or []:
        attributes = evidence.get('attributes') or {}
        if evidence.get('type') == 'JEV_RISK_REVIEW' and isinstance(attributes.get('riskProbability'), (int, float)):
            return f" JEV 위험 {attributes['riskProbability']:.2f}"
        if evidence.get('type') == 'JEV_TEST_REVIEW' and isinstance(attributes.get('adequateProbability'), (int, float)):
            return f" JEV 테스트 충분 {attributes['adequateProbability']:.2f}"
    return ''


def risk_summary(report, limit=5):
    findings = [item for item in report.get('findings', []) if item.get('severity') in ('CRITICAL', 'HIGH')]
    findings.sort(key=lambda item: SEVERITY_ORDER.index(item['severity']))
    notes = {item.get('code'): item.get('message') for item in report.get('diagnostics') or [] if isinstance(item, dict)}
    jev = ' (JEV 실패, 정적 결과만)' if 'JEV-001' in notes else ' (JEV 호출 한도 도달, 일부만 검토)' if 'JEV-002' in notes else ''
    if not findings:
        return ['위험 분석: HIGH·CRITICAL 없음' + jev]
    lines = [f'위험 분석: HIGH·CRITICAL {len(findings)}건' + jev]
    for item in findings[:limit]:
        location = item.get('location') or {}
        lines.append(f"  - {location.get('file')}:{location.get('startLine')} {item.get('ruleId')} {item.get('title')} "
                     f"({item.get('status', '').lower() or 'confirmed'}){_jev(item)}")
    if len(findings) > limit:
        lines.append(f'  - 외 {len(findings) - limit}건')
    return lines


def checks(task, cwd, edits, say, process=subprocess.run, jar=None):
    """build-convention and code-risk-analyzer on the project after a task that changed files; no model calls.

    Returns the lines of the failed checks (empty when everything passed or nothing ran), for the next task to fix.
    """
    plan = check_plan(task)
    findings = []
    if not edits or not any(plan.values()):
        if edits:
            say('검사: 요청대로 건너뜀')
        return findings
    try:
        project = find_project(Path(cwd))
    except ValueError:
        say('검사: Gradle 프로젝트가 아니라 건너뜀')
        return findings
    reports = project/REPORT_DIR
    reports.mkdir(parents=True, exist_ok=True)
    failed = False
    if plan['convention']:
        say('검사: build-convention (./gradlew check, 빌드 파일 수정 없음)...')
        result = process([sys.executable, str(SCRIPTS/'convention_check.py'), str(project), '--output', str(reports/'convention.json')],
                         cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=3600)
        lines = [line for line in (result.stdout or '').splitlines() if line.strip() and not line.startswith('리포트:')]
        for line in lines[:14]:
            say(line)
        if result.returncode == 1:
            failed = True
            findings += lines[:14]
    else:
        say('검사: build-convention은 요청대로 건너뜀')
    if plan['risk']:
        jar = Path(jar) if jar else DEFAULT_JAR
        sources = changed_sources(project, edits)
        if not jar.is_file():
            say('위험 분석: 분석기 jar가 없어 건너뜀 (install.sh)')
        elif not sources:
            say('위험 분석: 바뀐 src/main Java 파일이 없어 건너뜀')
        else:
            (reports/'changed.txt').write_text('\n'.join(sources) + '\n', encoding='utf-8')
            output = REPORT_DIR/'scan.json'
            environ = dict(os.environ)
            with_jev = load_key(environ)
            if not with_jev:
                say('위험 분석: JEV 키가 없어 정적으로 확정 못 한 항목(review)은 검토하지 않음')
            environ.setdefault('CODE_RISK_JEV_MAX_CALLS', str(JEV_MAX_CALLS))
            result = process(['java', '-jar', str(jar), 'scan', str(project), '--format', 'json', '--output', output.as_posix(),
                              '--files-file', (REPORT_DIR/'changed.txt').as_posix(), '--jev', 'true' if with_jev else 'false'],
                             cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=1800, env=environ)
            try:
                lines = risk_summary(json.loads((project/output).read_text(encoding='utf-8')))
            except (OSError, ValueError):
                lines = ['위험 분석: 실행 실패 - ' + next((line for line in reversed((result.stdout or '').splitlines())
                                                     if line.startswith('Error')), f'exit {result.returncode}')]
            for line in lines:
                say(line)
            if not lines[0].startswith('위험 분석: HIGH·CRITICAL 없음'):
                failed = True
                findings += lines
    else:
        say('위험 분석: 요청대로 건너뜀')
    if failed:
        say('고치려면: ai 검사 결과 고쳐줘 (이 결과가 다음 요청에 한 번 함께 전달됨)')
    return findings


def _prompt(name, text):
    return text if name == 'claude' else CONCISE + '\n\n' + text


def _state_file(environ):
    return Path(environ.get('XDG_STATE_HOME') or '~/.local/state').expanduser()/'ai-orchestration'/'conversations.json'


def _load(path):
    try:
        value = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _split(argv):
    """(agent or None, task text, --why, --new, --mcp)."""
    argv = list(sys.argv[1:] if argv is None else argv)
    why, new, mcp = '--why' in argv, '--new' in argv, '--mcp' in argv
    argv = [item for item in argv if item not in ('--why', '--new', '--mcp')]
    agent = argv.pop(0) if argv[:1] in (['claude'], ['codex']) else None
    return agent, ' '.join(argv).strip(), why, new, mcp


def _setting(name, tier, environ, say):
    try:
        return launch_setting(name, tier, default_routing(environ))
    except (ValueError, OSError, KeyError) as error:
        say(f'ai: 모델 설정을 쓰지 못해 CLI 기본값으로 실행합니다 ({error})')
        return None, None


def self_update(argv):
    """Pull this tool and rebuild what changed (self_update.py); after new commits, run the same command on the new code."""
    try:
        pulled = _module('self_update').update()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'업데이트 건너뜀: {error}', file=sys.stderr)
        return
    if pulled:
        sys.stdout.flush()
        os.execv(sys.executable, [sys.executable, str(Path(__file__).resolve()), *argv])


def main(argv=None, environ=os.environ, now=None, which=shutil.which, popen=subprocess.Popen, cwd=None, say=print,
         process=subprocess.run, update=None):
    agent, task, why_only, new, mcp = _split(argv)
    if update is not None and task and not why_only:
        update(sys.argv[1:] if argv is None else list(argv))
    cwd = Path(cwd or os.getcwd()).resolve()
    now = now or datetime.now(timezone.utc)
    installed = tuple(cli for cli in ('claude', 'codex') if which(cli))
    claude_root = Path(environ.get('CLAUDE_CONFIG_DIR') or '~/.claude').expanduser()
    codex_root = Path(environ.get('CODEX_HOME') or '~/.codex').expanduser()
    if agent:
        name, why, boost = agent, '직접 지정', False
    else:
        name, why, boost = choose(claude_state(claude_root, now), usage.codex_usage(codex_root, now, 7)['limits'], now, installed)
    if name is None:
        say(f'ai: {why}')
        return 1
    level = 'TIER_4' if boost else tier(task)[0] if task else None
    model, effort = _setting(name, level, environ, say)
    setting = ' '.join(item for item in (model, effort) if item) or 'CLI 기본값'
    say(f'ai: {name} ({why}) - {setting}' + (' [곧 초기화될 여유분이라 최고 설정]' if boost else f' [{level}]' if level else ''))
    if why_only or not task:
        if not why_only:
            say(USAGE)
        return 0
    if name not in installed:
        say(f'ai: {name} CLI가 설치되어 있지 않습니다')
        return 2
    state_path = _state_file(environ)
    conversations = _load(state_path)
    previous = conversations.get(str(cwd)) or {}
    resume = None
    if not new and previous.get('provider') == name and previous.get('session'):
        try:
            if now - datetime.fromisoformat(previous['at']) <= CONTINUE_WITHIN:
                resume = previous['session']
        except (KeyError, ValueError, TypeError):
            pass
    if resume:
        say('ai: 이 폴더의 이전 대화를 이어서 합니다 (새로 시작: ai --new ...)')
    elif not new and previous.get('provider') not in (None, name):
        say(f"ai: 이전 대화({previous['provider']})는 이어지지 않아 새로 시작합니다")
    pending = previous.get('checks') or []
    if pending:
        say('ai: 직전 검사에서 실패한 내용을 이번 요청에 함께 보냅니다')
    prompt = ('직전 작업 뒤 자동 검사에서 실패한 내용이다. 요청과 관련되면 함께 고쳐라:\n' + '\n'.join(pending[:40])
              + '\n\n요청: ' + task) if pending else task
    outcome = run(name, command(name, model, effort, resume, mcp), _prompt(name, prompt), cwd, say, popen)
    if resume and outcome['error'] and not outcome['session']:
        say('ai: 이전 대화를 열지 못해 새로 시작합니다')
        outcome = run(name, command(name, model, effort, None, mcp), _prompt(name, prompt), cwd, say, popen)
    if outcome['limited'] and not agent:
        other = 'codex' if name == 'claude' else 'claude'
        if other in installed:
            say(f'ai: {name} 한도에 걸려 {other}로 같은 작업을 이어서 합니다')
            name = other
            model, effort = _setting(name, level, environ, say)
            outcome = run(name, command(name, model, effort, None, mcp), _prompt(name, prompt), cwd, say, popen)
    failures = [] if outcome['error'] else checks(task, cwd, outcome['edits'], say, process)
    if outcome['session'] or failures or pending:
        conversations[str(cwd)] = {'provider': name, 'session': outcome['session'] or previous.get('session'),
                                   'at': now.isoformat() if outcome['session'] else previous.get('at', now.isoformat()),
                                   'checks': failures if not outcome['error'] else pending}
        try:
            state_path.parent.mkdir(parents=True, exist_ok=True)
            state_path.write_text(json.dumps(conversations, ensure_ascii=False, indent=2), encoding='utf-8')
        except OSError:
            pass
    if outcome['error']:
        say(f"ai: {name} 작업이 실패했습니다 - {outcome['error']}")
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(update=self_update))
