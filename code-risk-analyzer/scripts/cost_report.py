#!/usr/bin/env python3
"""Aggregate recorded CLI attempts into tokens, retries and cost per completed CODE_CHANGE session."""
import argparse
import json
from pathlib import Path

COUNTERS = ('inputTokens', 'cachedInputTokens', 'outputTokens')


def read_json(path):
    return json.loads(Path(path).read_text())


def attempts(directory):
    seen = {}
    # Repair archives hold copies of earlier artifacts; attempt identifiers keep each CLI call counted once.
    for path in sorted(directory.glob('agent-*.json')) + sorted(directory.glob('repair-*/agent-*.json')):
        artifact = read_json(path)
        for index, item in enumerate(artifact.get('attempts', [])):
            if not isinstance(item, dict):
                raise ValueError('Invalid attempt record: ' + str(path))
            key = item.get('attemptId') or '%s#%d#%s' % (path.relative_to(directory), index, item.get('promptSha256', ''))
            seen.setdefault(key, item)
    return list(seen.values())


def model_key(item):
    return '/'.join((item.get('provider') or 'unknown', item.get('model') or 'unknown', item.get('reasoningEffort') or 'default'))


def empty():
    return {'attempts': 0, 'acceptedAttempts': 0, 'attemptsWithoutUsage': 0, **dict.fromkeys(COUNTERS, 0),
            'costUsd': 0, 'durationSeconds': 0.0, 'sessionNames': set(), 'completedNames': set()}


def add(bucket, item, session, completed):
    bucket['attempts'] += 1
    bucket['acceptedAttempts'] += item.get('status') == 'COMPLETED'
    usage = item.get('usage') or {}
    if all(type(usage.get(k)) is int for k in COUNTERS):
        for name in COUNTERS:
            bucket[name] += usage[name]
    else:
        bucket['attemptsWithoutUsage'] += 1
    if isinstance(usage.get('costUsd'), (int, float)):
        bucket['costUsd'] += usage['costUsd']
    if isinstance(item.get('durationSeconds'), (int, float)):
        bucket['durationSeconds'] += item['durationSeconds']
    bucket['sessionNames'].add(session)
    if completed:
        bucket['completedNames'].add(session)


def finish(bucket):
    sessions, completed = bucket.pop('sessionNames'), bucket.pop('completedNames')
    bucket['sessions'], bucket['completedSessions'] = len(sessions), len(completed)
    bucket['costUsd'] = round(bucket['costUsd'], 6)
    bucket['durationSeconds'] = round(bucket['durationSeconds'], 3)
    tokens = bucket['inputTokens'] + bucket['outputTokens']
    known = bucket['completedSessions'] and not bucket['attemptsWithoutUsage']
    bucket['tokensPerCompletedSession'] = round(tokens / bucket['completedSessions'], 1) if known else None
    bucket['costPerCompletedSession'] = round(bucket['costUsd'] / bucket['completedSessions'], 6) \
        if bucket['completedSessions'] and bucket['costUsd'] else None
    return bucket


def build(directories):
    total, models, repairs = empty(), {}, 0
    for directory in directories:
        directory = Path(directory)
        state_path = directory/'state.json'
        if not state_path.is_file():
            raise ValueError('Session directory with state.json required: ' + str(directory))
        state = read_json(state_path)
        completed = state.get('phase') == 'COMPLETED'
        repairs += int((state.get('repair') or {}).get('iterations', 0))
        name = str(directory.resolve())
        total['sessionNames'].add(name)
        if completed:
            total['completedNames'].add(name)
        for item in attempts(directory):
            add(total, item, name, completed)
            add(models.setdefault(model_key(item), empty()), item, name, completed)
    total = finish(total)
    total['retries'] = total['attempts'] - total['acceptedAttempts']
    total['repairIterations'] = repairs
    result_total = {key: total[key] for key in ('sessions', 'completedSessions', 'attempts', 'acceptedAttempts', 'retries',
                                                 'repairIterations', *COUNTERS, 'costUsd', 'durationSeconds',
                                                 'tokensPerCompletedSession')}
    if total['attemptsWithoutUsage']:
        result_total['attemptsWithoutUsage'] = total['attemptsWithoutUsage']
    return {'version': 1, 'total': result_total, 'models': {key: finish(value) for key, value in sorted(models.items())},
            'meaning': 'Recorded CLI usage only; JEV calls, human review time and failed sessions without artifacts are not included'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sessions', nargs='+', help='Session directories that contain state.json')
    args = parser.parse_args()
    try:
        print(json.dumps(build(args.sessions), indent=2, ensure_ascii=False))
        return 0
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print('cost report failed: ' + str(error))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
