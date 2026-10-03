"""Instantiate explicitly reviewed fixture definitions for the current executed snapshot."""
from pathlib import Path
from verify_service import workflow

PROJECT = Path(__file__).resolve().parents[1]


def build_reviews(snapshot, report_path='build/scan-reviewed.json', output_directory=None):
    review = workflow.review_module()
    definitions = workflow.read_json(PROJECT / 'review-definitions.json')
    # Review meanings are pinned to approved inputs, never automatically refreshed after code changes.
    for path, expected in definitions['approvedHashes'].items():
        if review.digest(review.safe_path(PROJECT, path)) != expected:
            raise ValueError('Fixture review definition is stale; explicit re-review required')
    requirement_path = 'review-requirements.md'
    requirements = (PROJECT / requirement_path).read_text().splitlines()
    records = []
    for definition in definitions['records']:
        record = {key: definition[key] for key in ('id', 'category', 'decision', 'reason', 'reviewer')}
        record['sourceSha256'] = definitions['approvedHashes'][definition['source']]
        requirement = definition['requirement']
        line = next(index+1 for index, text in enumerate(requirements) if text.startswith(requirement + ':'))
        record['requirement'] = {'id': requirement, 'path': requirement_path,
                                 'sha256': definitions['approvedHashes'][requirement_path],
                                 'line': line, 'text': requirements[line-1]}
        proofs = []
        for selected in definition['tests']:
            test = selected['test']
            classname, method = test.split('#')
            path = selected['path']
            text = (PROJECT / path).read_text()
            lines = text.splitlines()
            method_line = next(index+1 for index, text in enumerate(lines) if 'void ' + method + '(' in text)
            start, end, _ = review.method_range(text, method_line, method)
            assertions = []
            for expected in selected['assertions']:
                matches = [index+1 for index in range(start-1, end) if lines[index].strip() == expected]
                if not matches:
                    raise ValueError('Reviewed assertion no longer exists in the selected test')
                assertions.extend({'line': index, 'text': expected} for index in matches)
            junit = 'TEST-' + classname + '.xml'
            proofs.append({'test': test, 'path': path, 'sha256': definitions['approvedHashes'][path],
                           'methodLine': method_line, 'assertions': assertions,
                           'junit': {'file': junit, 'sha256': snapshot['junitSha256'][junit]}})
        record['tests'] = proofs
        records.append(record)
    bundle = {'version': 1, 'snapshotSha256': review.fingerprint(snapshot),
              'reportSha256': review.digest(review.safe_path(PROJECT, report_path)), 'records': records}
    policy = workflow.read_json(PROJECT / 'fixture-review-policy.json')
    policy['project'] = str((PROJECT / policy['project']).resolve())
    output = Path(output_directory) if output_directory else PROJECT/'build'
    if not output.resolve().is_relative_to(PROJECT/'build'):
        raise ValueError('Fixture review output must be under build')
    workflow.save(output / 'evidence-reviews.json', bundle)
    result = review.review_evidence(PROJECT, snapshot, report_path, bundle, policy)
    workflow.save(output / 'evidence-classification.json', result)
    return result
