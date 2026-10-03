"""Measure ask.py intent decisions against a labeled sentence set with real JEV calls.

Only the sentences are sent. The report keeps raw JEV responses of failed validations so the cause can be fixed;
it never contains the API key. WRONG_RUN (a different task would run) is the number that must stay at zero.
"""
import argparse
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location('ask', Path(__file__).resolve().with_name('ask.py'))
ask = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ask)


def outcome(expected, decided):
    if decided is None:
        return 'SAFE_REJECT' if expected == 'REJECT' else 'OVER_REJECT'
    return 'CORRECT' if decided == expected else 'WRONG_RUN'


def evaluate(items, request, repeat=1):
    rows = []
    for item in items:
        for attempt in range(repeat):
            raw, answer = None, None
            try:
                raw = request(item['text'], 30)
                answer = dict(ask.intent_answer(json.loads(raw.decode('utf-8'))), status='COMPLETED')
            except (OSError, ValueError, TypeError, KeyError, UnicodeError) as error:
                answer = {'status': 'FAILED', 'reason': f'{type(error).__name__}: {error}'[:200]}
            try:
                decided, _ = ask.decide(item['text'], lambda _text: answer)
                error = None
            except ValueError as rejection:
                decided, error = None, str(rejection)
            row = {'text': item['text'], 'expected': item['expected'], 'attempt': attempt, 'jev': answer.get('intent'),
                   'confidence': answer.get('confidence'), 'decided': decided, 'outcome': outcome(item['expected'], decided)}
            if answer['status'] != 'COMPLETED':
                row['failure'] = answer['reason']
                row['raw'] = raw.decode('utf-8', 'replace')[:4000] if raw else None
            if error:
                row['rejection'] = error
            rows.append(row)
    summary = {}
    for row in rows:
        summary[row['outcome']] = summary.get(row['outcome'], 0) + 1
    summary['JEV_FAILED'] = sum('failure' in row for row in rows)
    jev_right = [row for row in rows if row['jev'] is not None]
    summary['JEV_ACCURACY'] = round(sum(row['jev'] == (row['expected'] if row['expected'] != 'REJECT' else 'UNKNOWN')
                                        for row in jev_right) / len(jev_right), 3) if jev_right else None
    return {'summary': summary, 'rows': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cases')
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if not 1 <= args.repeat <= 3:
        raise SystemExit('repeat must be 1..3')
    report = evaluate(json.loads(Path(args.cases).read_text(encoding='utf-8'))['items'], ask.jev_request, args.repeat)
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(report['summary'], ensure_ascii=False))
    for row in report['rows']:
        if row['outcome'] != 'CORRECT' and row['outcome'] != 'SAFE_REJECT' or 'failure' in row:
            print(json.dumps({k: row[k] for k in row if k != 'raw'}, ensure_ascii=False))
    return 1 if report['summary'].get('WRONG_RUN') else 0


if __name__ == '__main__':
    raise SystemExit(main())
