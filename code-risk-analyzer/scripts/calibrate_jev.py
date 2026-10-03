#!/usr/bin/env python3
"""Compare versioned capability questions on labeled repository tasks and holdouts."""
import argparse
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import time


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


jev = module('jev_capability')
agent = module('agent_runner')
router = module('model_router')
VERSION = 1


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, allow_nan=False).encode()).hexdigest()


def state_sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False).encode()).hexdigest()


def load_cases(project, path):
    corpus = agent.parse_json(path.read_text())
    if set(corpus) != {'version', 'cases'} or corpus['version'] != VERSION or not isinstance(corpus['cases'], list):
        raise ValueError('Labeled corpus version 1 required')
    cases, ids = [], set()
    for case in corpus['cases']:
        if not isinstance(case, dict) or set(case) != {'id', 'split', 'task', 'context', 'expectedTier', 'expectedRisk'} \
                or not isinstance(case['id'], str) or case['id'] in ids or case['split'] not in ('calibration', 'holdout') \
                or case['expectedTier'] not in (*router.TIERS, 'UNKNOWN') or case['expectedRisk'] not in ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN'):
            raise ValueError('Distinct labeled calibration/holdout cases required')
        ids.add(case['id'])
        context, hashes = {}, {}
        for reference in case['context']:
            if not isinstance(reference, dict) or not {'path'} <= set(reference) or not set(reference) <= {'path', 'symbol'}:
                raise ValueError('Explicit repository context references required')
            relative = Path(reference['path'])
            target = project/relative
            if relative.is_absolute() or '..' in relative.parts or any(p.is_symlink() for p in [target, *target.parents]):
                raise ValueError('Unsafe calibration context')
            text = target.read_text()
            hashes[relative.as_posix()] = hashlib.sha256(text.encode()).hexdigest()
            if 'symbol' in reference:
                parts = reference['symbol'].split('.')
                nodes = ast.parse(text).body
                for part in parts:
                    matches = [node for node in nodes if getattr(node, 'name', None) == part]
                    if len(matches) != 1:
                        raise ValueError('Calibration symbol missing or ambiguous')
                    node = matches[0]
                    nodes = getattr(node, 'body', [])
                text = '\n'.join(text.splitlines()[node.lineno-1:node.end_lineno])
            context[relative.as_posix()] = text
        state = {'stage': 'implementation', 'requirements': {'task': case['task']},
                 'allowedFiles': list(context), 'contextFiles': context, 'acceptedTests': []}
        cases.append(dict(case, state=state, contextHashes=hashes))
    if len(cases) > 40 or any(sum(case['split'] == split for case in cases) < 4 for split in ('calibration', 'holdout')):
        raise ValueError('Four cases per split required; at most forty cases')
    return cases


def metrics(records, split):
    selected = [record for record in records if record['split'] == split]
    known = [record for record in selected if record['expectedTier'] != 'UNKNOWN']
    accepted = [record for record in known if record['result']['status'] == 'COMPLETED']
    wrong = [record['id'] for record in accepted if record['result']['requiredTier'] != record['expectedTier']
             or record['result']['risk'] != record['expectedRisk']]
    unsafe = [record['id'] for record in accepted if record['expectedRisk'] == 'HIGH' and record['result']['risk'] != 'HIGH']
    unknown_passed = [record['id'] for record in selected if record['expectedTier'] == 'UNKNOWN' and record['result']['status'] == 'COMPLETED']
    return {'cases': len(selected), 'knownCases': len(known), 'accepted': len(accepted),
            'acceptanceCoverage': len(accepted)/len(known) if known else 0.0,
            'acceptedWrong': wrong, 'highRiskUnderRouting': unsafe, 'unknownAccepted': unknown_passed,
            'acceptedPrecision': (len(accepted)-len(wrong))/len(accepted) if accepted else 0.0}


def evaluate(project, corpus_path, output, *, versions=(1, 2), model='jev-latest'):
    cases = load_cases(project, corpus_path)
    profiles = {}
    for version in versions:
        config = router.capability_settings({'model': model, 'questionVersion': version})
        records = []
        for case in cases:
            start = time.monotonic()
            result = jev.judge(config, case['state'], agent.parse_json)
            record = {key: case[key] for key in ('id', 'split', 'expectedTier', 'expectedRisk', 'contextHashes')}
            record.update(result=result, elapsedSeconds=round(time.monotonic()-start, 3))
            records.append(record)
            # The receipt never stores credentials, response bodies or raw build output.
            output.mkdir(parents=True, exist_ok=True)
            (output/('v'+str(version)+'-'+case['id']+'.json')).write_text(json.dumps(record, indent=2)+'\n')
        calibration, holdout = metrics(records, 'calibration'), metrics(records, 'holdout')
        passed = all(value['acceptedPrecision'] == 1.0 and value['acceptanceCoverage'] >= 0.5
                     and not value['highRiskUnderRouting'] and not value['unknownAccepted'] for value in (calibration, holdout))
        profiles[str(version)] = {'status': 'PASSED' if passed else 'NEEDS_REVIEW', 'calibration': calibration, 'holdout': holdout,
                                  'questionsSha256': fingerprint(jev.questions(version)),
                                  'minConfidence': config['minConfidence'], 'records': records}
    report = {'version': VERSION, 'model': model, 'project': str(project), 'corpus': str(corpus_path),
              'corpusSha256': hashlib.sha256(corpus_path.read_bytes()).hexdigest(),
              'jevToolSha256': hashlib.sha256(Path(jev.__file__).read_bytes()).hexdigest(),
              'contextProjectionVersion': jev.CONTEXT_PROJECTION_VERSION, 'profiles': profiles,
              'limitations': ['Initial repository task corpus; not a production success-rate or a 200-task model benchmark.',
                              'Holdouts and labels are explicit review inputs; confidence measures distribution concentration, not correctness.']}
    (output/'summary.json').write_text(json.dumps(report, indent=2)+'\n')
    return report


def validate_receipt(path, config):
    receipt = agent.parse_json(Path(path).read_text())
    profile = receipt['profiles'][str(config.get('questionVersion', 1))]
    if receipt['version'] != VERSION or receipt['model'] != config['model'] or profile['status'] != 'PASSED' \
            or receipt['contextProjectionVersion'] != jev.CONTEXT_PROJECTION_VERSION \
            or profile['minConfidence'] != config['minConfidence'] \
            or receipt['jevToolSha256'] != hashlib.sha256(Path(jev.__file__).read_bytes()).hexdigest() \
            or profile['questionsSha256'] != fingerprint(jev.questions(config.get('questionVersion', 1))):
        raise ValueError('Current passed calibration receipt required')
    if hashlib.sha256(Path(receipt['corpus']).read_bytes()).hexdigest() != receipt['corpusSha256']:
        raise ValueError('Calibration corpus changed')
    project = Path(receipt['project'])
    cases = load_cases(project, Path(receipt['corpus']))
    trials = len(profile['records'])//len(cases)
    if not 1 <= trials <= 3 or len(profile['records']) != trials*len(cases):
        raise ValueError('Complete bounded calibration trials required')
    expected = cases*trials
    if [case['id'] for case in expected] != [record['id'] for record in profile['records']] \
            or any(state_sha(case['state']) != record['result'].get('stateSha256') or case['split'] != record['split']
                   or case['expectedTier'] != record['expectedTier'] or case['expectedRisk'] != record['expectedRisk']
                   for case, record in zip(expected, profile['records'])):
        raise ValueError('Calibration source or labels changed')
    for split in ('calibration', 'holdout'):
        observed = metrics(profile['records'], split)
        if observed != profile[split] or observed['acceptedPrecision'] != 1.0 or observed['acceptanceCoverage'] < 0.5 \
                or observed['highRiskUnderRouting'] or observed['unknownAccepted']:
            raise ValueError('Calibration quality gate not satisfied')
    return receipt


def combine_reports(paths, output):
    """Measure variance across complete observed trials of identical frozen requests."""
    if not 2 <= len(paths) <= 3:
        raise ValueError('Two or three completed calibration trials required')
    reports = [agent.parse_json(Path(path).read_text()) for path in paths]
    result = dict(reports[0])
    profile_versions = set.intersection(*(set(report['profiles']) for report in reports))
    profiles = {}
    for report in reports[1:]:
        if any(report[key] != result[key] for key in ('version', 'model', 'corpusSha256', 'jevToolSha256', 'contextProjectionVersion')):
            raise ValueError('Only identical frozen calibration versions can be combined')
    for version in profile_versions:
        first = reports[0]['profiles'][version]
        expected = [(record['id'], record['result'].get('stateSha256')) for record in first['records']]
        records = []
        for trial, report in enumerate(reports):
            current = report['profiles'][version]
            if current['questionsSha256'] != first['questionsSha256'] or current['minConfidence'] != first['minConfidence'] \
                    or [(record['id'], record['result'].get('stateSha256')) for record in current['records']] != expected:
                raise ValueError('Calibration task requests changed between trials')
            records += [dict(record, trial=trial+1) for record in current['records']]
        values = {split: metrics(records, split) for split in ('calibration', 'holdout')}
        passed = all(value['acceptedPrecision'] == 1.0 and value['acceptanceCoverage'] >= 0.5
                     and not value['highRiskUnderRouting'] and not value['unknownAccepted'] for value in values.values())
        profiles[version] = dict(first, **values, records=records, trials=len(reports),
                                 status='PASSED' if passed else 'NEEDS_REVIEW')
    result['profiles'] = profiles
    result['trialReceipts'] = {str(Path(path).resolve()): hashlib.sha256(Path(path).read_bytes()).hexdigest() for path in paths}
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(json.dumps(result, indent=2)+'\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True)
    parser.add_argument('--corpus', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--model', default='jev-latest')
    args = parser.parse_args()
    try:
        report = evaluate(Path(args.project).resolve(), Path(args.corpus).resolve(), Path(args.output).resolve(), model=args.model)
        print(json.dumps({version: {name: value[name] for name in ('status', 'calibration', 'holdout')}
                          for version, value in report['profiles'].items()}))
        return 0 if report['profiles']['2']['status'] == 'PASSED' else 3
    except (ValueError, KeyError, TypeError, OSError):
        print('Calibration rejected; inspect explicit corpus and sanitized receipts')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
