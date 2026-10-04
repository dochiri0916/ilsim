"""Claude Code and Codex usage on this PC, read from the CLIs' own local logs (no network, no tokens spent).

- Claude Code: ~/.claude/projects/**/*.jsonl (or CLAUDE_CONFIG_DIR). Tokens per model for the last 5 hours, today and
  the last 7 days. These logs hold no limit percentages; `ai` records the 5-hour and weekly ones that `claude -p`
  reports while it works (rate_limit_event) in ~/.local/state/ai-orchestration/claude-limits.json, shown from there.
- Codex: ~/.codex/sessions/**/*.jsonl (or CODEX_HOME). Tokens per model for the same periods, and the most recent
  5-hour and weekly limit percentages Codex recorded, with their reset times.

    python3 scripts/usage_report.py [--days 7] [--json]
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys

WINDOW = timedelta(hours=5)
# rate_limit_event window names in `claude -p --output-format stream-json`.
CLAUDE_WINDOWS = {'five_hour': 'fiveHour', 'seven_day': 'weekly'}


def _time(value):
    if not isinstance(value, str):
        return None
    try:
        moment = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)


def _lines(path):
    try:
        with open(path, encoding='utf-8', errors='replace') as handle:
            for line in handle:
                try:
                    yield json.loads(line)
                except ValueError:
                    continue
    except OSError:
        return


def _number(value):
    return value if type(value) is int and value >= 0 else 0


def _empty():
    return {'input': 0, 'cachedInput': 0, 'cacheWrite': 0, 'output': 0, 'requests': 0}


def _periods(now, days):
    local_midnight = now.astimezone().replace(hour=0, minute=0, second=0, microsecond=0)
    return {'last5h': now - WINDOW, 'today': local_midnight.astimezone(timezone.utc),
            f'last{days}d': now - timedelta(days=days)}


def _add(result, periods, moment, model, counts):
    for name, start in periods.items():
        if moment >= start:
            bucket = result[name].setdefault(model or 'unknown', _empty())
            for key, value in counts.items():
                bucket[key] += value
            bucket['requests'] += 1


def claude_usage(root, now, days=7):
    """{'periods': {name: {model: counts}}, 'files': n}; each API message counted once across resumed transcripts."""
    periods = _periods(now, days)
    result = {name: {} for name in periods}
    seen = set()
    files = sorted(Path(root).glob('projects/**/*.jsonl')) if Path(root).is_dir() else []
    oldest = min(periods.values())
    for path in files:
        try:
            if datetime.fromtimestamp(path.stat().st_mtime, timezone.utc) < oldest:
                continue
        except OSError:
            continue
        for entry in _lines(path):
            message = entry.get('message') if isinstance(entry, dict) else None
            if entry.get('type') != 'assistant' or not isinstance(message, dict) or not isinstance(message.get('usage'), dict):
                continue
            moment = _time(entry.get('timestamp'))
            if moment is None or moment < oldest:
                continue
            key = (message.get('id'), entry.get('requestId'))
            if key != (None, None):
                if key in seen:
                    continue
                seen.add(key)
            usage = message['usage']
            _add(result, periods, moment, message.get('model'), {
                'input': _number(usage.get('input_tokens')), 'cachedInput': _number(usage.get('cache_read_input_tokens')),
                'cacheWrite': _number(usage.get('cache_creation_input_tokens')), 'output': _number(usage.get('output_tokens'))})
    return {'periods': result, 'files': len(files)}


def _limit(value, observed):
    if not isinstance(value, dict) or type(value.get('used_percent')) not in (int, float):
        return None
    limit = {'usedPercent': float(value['used_percent'])}
    if type(value.get('window_minutes')) is int:
        limit['windowMinutes'] = value['window_minutes']
    resets = None
    if type(value.get('resets_at')) in (int, float):
        resets = datetime.fromtimestamp(value['resets_at'], timezone.utc)
    elif type(value.get('resets_in_seconds')) in (int, float) and observed is not None:
        resets = observed + timedelta(seconds=value['resets_in_seconds'])
    if resets is not None:
        limit['resetsAt'] = resets.isoformat()
    return limit


def claude_limits_file(environ=os.environ):
    return Path(environ.get('XDG_STATE_HOME') or '~/.local/state').expanduser()/'ai-orchestration'/'claude-limits.json'


def _claude_window(value, observed):
    """{'usedPercent', 'resetsAt', 'observedAt'} of one window; utilization is a 0-1 fraction, resetsAt epoch seconds."""
    if not isinstance(value, dict):
        return None
    used = value.get('utilization')
    if type(used) in (int, float):
        used = min(100.0, max(0.0, used * 100))
    elif value.get('status') == 'rejected':
        used = 100.0
    else:
        return None
    limit = {'usedPercent': used, 'observedAt': observed.isoformat()}
    resets = value.get('resetsAt')
    if type(resets) in (int, float) and resets > 0:
        limit['resetsAt'] = datetime.fromtimestamp(resets / 1000 if resets > 1e11 else resets, timezone.utc).isoformat()
    return limit


def claude_limits_from(info, observed):
    """{'fiveHour': ..., 'weekly': ...} found in the rate_limit_info of a claude -p rate_limit_event."""
    if not isinstance(info, dict):
        return {}
    windows = info.get('unifiedWindows') if isinstance(info.get('unifiedWindows'), dict) else {}
    result = {}
    for name, key in CLAUDE_WINDOWS.items():
        limit = _claude_window(windows.get(name), observed)
        if limit:
            result[key] = limit
    key = CLAUDE_WINDOWS.get(info.get('rateLimitType'))
    if key and key not in result:
        limit = _claude_window(info, observed)
        if limit:
            result[key] = limit
    return result


def load_claude_limits(path):
    """The recorded Claude limits in the shape of codex_usage()['limits'], or None."""
    try:
        value = json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None
    limits = {key: value[key] for key in CLAUDE_WINDOWS.values() if isinstance(value, dict) and isinstance(value.get(key), dict)
              and type(value[key].get('usedPercent')) in (int, float) and isinstance(value[key].get('observedAt'), str)}
    if not limits:
        return None
    limits['observedAt'] = max(limit['observedAt'] for limit in limits.values())
    return limits


def save_claude_limits(path, info, observed):
    """Merge the windows of one rate_limit_info into the record; False when it held none."""
    found = claude_limits_from(info, observed)
    if not found:
        return False
    record = load_claude_limits(path) or {}
    record.pop('observedAt', None)
    record.update(found)
    try:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
    except OSError:
        return False
    return True


def codex_usage(root, now, days=7):
    """{'periods': ..., 'limits': {...} or None, 'files': n}; token_count totals are cumulative per session file."""
    periods = _periods(now, days)
    result = {name: {} for name in periods}
    latest = None
    root = Path(root)
    files = sorted(root.glob('sessions/**/*.jsonl')) + sorted(root.glob('archived_sessions/**/*.jsonl')) if root.is_dir() else []
    oldest = min(periods.values())
    for path in files:
        try:
            if datetime.fromtimestamp(path.stat().st_mtime, timezone.utc) < oldest:
                continue
        except OSError:
            continue
        model, previous = None, None
        for entry in _lines(path):
            if not isinstance(entry, dict) or not isinstance(entry.get('payload'), dict):
                continue
            payload, moment = entry['payload'], _time(entry.get('timestamp'))
            if entry.get('type') == 'turn_context' and isinstance(payload.get('model'), str):
                model = payload['model']
                continue
            if entry.get('type') != 'event_msg' or payload.get('type') != 'token_count' or moment is None:
                continue
            limits = payload.get('rate_limits')
            if isinstance(limits, dict) and (latest is None or moment > latest[0]):
                latest = (moment, limits)
            info = payload.get('info')
            total = info.get('total_token_usage') if isinstance(info, dict) else None
            if not isinstance(total, dict):
                continue
            current = {'input': _number(total.get('input_tokens')), 'cachedInput': _number(total.get('cached_input_tokens')),
                       'output': _number(total.get('output_tokens'))}
            if current == previous:
                continue  # repeated snapshot without a new request
            delta = {key: max(0, value - (previous or {}).get(key, 0)) for key, value in current.items()}
            # Codex counts cached input inside input_tokens.
            delta['input'] = max(0, delta['input'] - delta['cachedInput'])
            previous = current
            if moment >= oldest:
                _add(result, periods, moment, model, delta)
    limits = None
    if latest is not None:
        observed, raw = latest
        limits = {'observedAt': observed.isoformat(),
                  'fiveHour': _limit(raw.get('primary'), observed), 'weekly': _limit(raw.get('secondary'), observed)}
        if isinstance(raw.get('plan_type'), str):
            limits['plan'] = raw['plan_type']
    return {'periods': result, 'limits': limits, 'files': len(files)}


def _tokens(counts):
    total = counts['input'] + counts['cachedInput'] + counts.get('cacheWrite', 0) + counts['output']

    def short(number):
        return f'{number / 1_000_000:.1f}M' if number >= 1_000_000 else f'{number / 1000:.1f}k' if number >= 1000 else str(number)
    cache = f", 캐시 읽기 {short(counts['cachedInput'])}" + (f", 캐시 쓰기 {short(counts['cacheWrite'])}" if counts.get('cacheWrite') else '')
    return f"{short(total)} (입력 {short(counts['input'])}{cache}, 출력 {short(counts['output'])}, 요청 {counts['requests']})"


def _period_lines(periods, labels):
    lines = []
    for name, label in labels.items():
        models = periods.get(name, {})
        if not models:
            lines.append(f'  {label}: 기록 없음')
            continue
        lines.append(f'  {label}:')
        for model, counts in sorted(models.items(), key=lambda item: -item[1]['output']):
            lines.append(f'    - {model}: {_tokens(counts)}')
    return lines


def _reset(limit, now):
    if 'resetsAt' not in limit:
        return ''
    resets = datetime.fromisoformat(limit['resetsAt'])
    if resets <= now:
        return ' (기록 이후 초기화됨)'
    left = resets - now
    hours, minutes = divmod(int(left.total_seconds()) // 60, 60)
    return f" ({resets.astimezone().strftime('%m-%d %H:%M')} 초기화, {hours}시간 {minutes}분 남음)"


def _limit_lines(limits, now, missing):
    if not limits:
        return [f'  한도 기록이 아직 없습니다 ({missing})']
    observed = datetime.fromisoformat(limits['observedAt']).astimezone().strftime('%m-%d %H:%M')
    plan = f", 요금제 {limits['plan']}" if limits.get('plan') else ''
    lines = [f'  한도 ({observed} 마지막 기록{plan}):']
    for key, label in (('fiveHour', '5시간'), ('weekly', '주간')):
        if limits.get(key):
            limit = limits[key]
            reset = 'resetsAt' in limit and datetime.fromisoformat(limit['resetsAt']) <= now
            left = 100.0 if reset else max(0.0, 100.0 - limit['usedPercent'])
            lines.append(f"    - {label}: {left:.0f}% 남음{_reset(limit, now)}")
    return lines


def report(claude, codex, now, days=7):
    labels = {'last5h': '최근 5시간', 'today': '오늘', f'last{days}d': f'최근 {days}일'}
    lines = ['Claude Code 사용량 (로컬 기록):']
    lines += _period_lines(claude['periods'], labels) if claude['files'] else ['  기록 파일이 없습니다 (~/.claude/projects)']
    lines += _limit_lines(claude.get('limits'), now, 'Claude CLI에 로그인돼 있으면 ai --usage가 받아 옵니다')
    lines.append('Codex 사용량 (로컬 기록):')
    if not codex['files']:
        lines.append('  기록 파일이 없습니다 (~/.codex/sessions)')
    else:
        lines += _period_lines(codex['periods'], labels)
        lines += _limit_lines(codex['limits'], now, 'Codex를 한 번 쓰면 남습니다')
    return '\n'.join(lines)


def main(argv=None, environ=os.environ, now=None):
    parser = argparse.ArgumentParser(description='Claude Code and Codex usage from local logs')
    parser.add_argument('--days', type=int, default=7)
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args(argv)
    if not 1 <= args.days <= 90:
        print('--days는 1~90입니다')
        return 2
    now = now or datetime.now(timezone.utc)
    claude_root = Path(environ.get('CLAUDE_CONFIG_DIR') or '~/.claude').expanduser()
    codex_root = Path(environ.get('CODEX_HOME') or '~/.codex').expanduser()
    claude, codex = claude_usage(claude_root, now, args.days), codex_usage(codex_root, now, args.days)
    claude['limits'] = load_claude_limits(claude_limits_file(environ))
    if args.json:
        print(json.dumps({'claude': claude, 'codex': codex}, ensure_ascii=False, indent=2))
    else:
        print(report(claude, codex, now, args.days))
    return 0


if __name__ == '__main__':
    sys.exit(main())
