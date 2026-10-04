"""Start Claude Code or Codex, whichever the plan limits favor right now, in the current folder.

    ai                        pick by usage and start it
    ai 결제 동시성 버그 고쳐줘  same, starting with that task; its risk words choose the model and reasoning level
    ai claude | codex [...]   start that one
    ai --why [...]            only print the choice, the reason and the model settings
    ai ... -- <args>          pass the rest to the chosen CLI as is

Reads the CLIs' own local logs (usage_report.py); no network, no tokens. Codex records its 5-hour and weekly limit
percentages with reset times. Claude Code records no percentages, so its state is estimated: a recorded "limit reached"
message blocks it until its reset, and its 5-hour window is taken to start at the first message after the last window.

Rules, in order:
1. A CLI that hit its limit (or has less than BLOCKED_BELOW percent left) and has not reset yet is skipped.
2. Capacity that resets within EXPIRING_MINUTES is used first: unused, it is lost at the reset.
3. Codex running ahead of its weekly pace is spared for Claude; so is Codex with little 5-hour room left.
4. Otherwise Codex, whose remaining room is known.

Model and reasoning: capacity about to reset with plenty left (BOOST_LEFT percent or more) gets the TIER_4 route's
strongest setting for the chosen CLI; otherwise a task sentence gets ask's tier (TIER_3 for risk words such as payment,
concurrency or transaction, else TIER_2). Without either the CLI's own default is used. Routes come from
config/ask/routing-policy.json, so forbidden models and efforts are never chosen.
"""
from datetime import datetime, timedelta, timezone
import importlib.util
import os
from pathlib import Path
import re
import shutil
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


def launch_args(name, tier, routing):
    """--model and reasoning flags for the chosen CLI from the tier's route, strongest setting of that provider."""
    if tier is None:
        return []
    ask = _module('ask')
    ask.models({'routing': {'registry': routing['registry'], 'policy': routing['policy']}})  # refuses forbidden routes
    registry, policy = ask._read(routing['registry']), ask._read(routing['policy'])
    route = policy['tiers'].get(tier)
    if route is None:
        return []
    steps = [step for step in [route, *route.get('fallbacks', [])] if registry['models'][step['modelId']]['adapter'] == name]
    if not steps:
        return []
    step = max(steps, key=lambda item: EFFORT_ORDER.index(item['reasoningEffort']) if item['reasoningEffort'] in EFFORT_ORDER else -1)
    model, effort = registry['models'][step['modelId']]['model'], step['reasoningEffort']
    if name == 'codex':
        return ['-m', model, '-c', f'model_reasoning_effort="{effort}"']
    return ['--model', model, '--effort', effort]


def _split(argv):
    """(agent or None, task words, --why, arguments after --)."""
    argv = list(sys.argv[1:] if argv is None else argv)
    extra = []
    if '--' in argv:
        extra, argv = argv[argv.index('--') + 1:], argv[:argv.index('--')]
    why = '--why' in argv
    argv = [item for item in argv if item != '--why']
    agent = argv.pop(0) if argv[:1] in (['claude'], ['codex']) else None
    return agent, ' '.join(argv).strip(), why, extra


def main(argv=None, environ=os.environ, now=None, execvp=os.execvp, which=shutil.which):
    agent, task, why_only, rest = _split(argv)
    boost = False
    if agent:
        name, why = agent, '직접 지정'
    else:
        now = now or datetime.now(timezone.utc)
        claude_root = Path(environ.get('CLAUDE_CONFIG_DIR') or '~/.claude').expanduser()
        codex_root = Path(environ.get('CODEX_HOME') or '~/.codex').expanduser()
        installed = tuple(cli for cli in ('claude', 'codex') if which(cli))
        name, why, boost = choose(claude_state(claude_root, now), usage.codex_usage(codex_root, now, 7)['limits'], now, installed)
    if name is None:
        print(f'ai: {why}')
        return 1
    tier = 'TIER_4' if boost else _module('ask').tier(task)[0] if task else None
    try:
        flags = launch_args(name, tier, _module('ask').default_routing(environ))
    except (ValueError, OSError, KeyError) as error:
        print(f'ai: 모델 설정을 쓰지 못해 CLI 기본값으로 실행합니다 ({error})', file=sys.stderr)
        flags = []
    setting = ' '.join(flags[1::2]) if flags else 'CLI 기본값'
    print(f'ai: {name} ({why}) - {setting}' + (' [곧 초기화될 여유분이라 최고 설정]' if boost else f' [{tier}]' if tier else ''), file=sys.stderr)
    if why_only:
        return 0
    if not which(name):
        print(f'ai: {name} CLI가 설치되어 있지 않습니다')
        return 2
    sys.stdout.flush()
    execvp(name, [name, *flags, *rest, *([task] if task else [])])
    return 0


if __name__ == '__main__':
    sys.exit(main())
