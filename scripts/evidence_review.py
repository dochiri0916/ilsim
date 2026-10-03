"""Bind explicit human reviews to static observations and fresh execution evidence."""
import hashlib
import json
from pathlib import Path
import re


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def safe_path(project, relative):
    if not isinstance(relative, str) or not relative or '\\' in relative:
        raise ValueError('Invalid evidence path')
    path = Path(relative)
    if path.is_absolute() or '..' in path.parts or path.as_posix() != relative:
        raise ValueError('Evidence must use normalized project-relative paths')
    target = project / path
    if any(p.is_symlink() for p in [target, *target.parents] if p.is_relative_to(project)):
        raise ValueError('Symlink evidence forbidden')
    if not target.resolve().is_relative_to(project) or not target.is_file():
        raise ValueError('Evidence file missing')
    return target


def read_report(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate evidence key')
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=pairs)


def items_from_report(report):
    result = {}
    if not isinstance(report, dict) or not isinstance(report.get('findings'), list):
        raise ValueError('Static report requires findings')
    for finding in report['findings']:
        location = finding['location']
        if finding['ruleId'] != 'TEST-001':
            identifier = 'risk:{}:{}:{}'.format(finding['ruleId'], location['file'], location['startLine'])
            candidates = [(identifier, 'PRODUCTION_RISK', finding['status'], finding['ruleId'], [])]
        else:
            candidates = [(e['attributes']['obligationId'], 'TEST_GAP', e['attributes']['coverageStatus'],
                           e['attributes'].get('riskId', ''), e['attributes'].get('tests', []))
                          for e in finding.get('evidence', []) if e['type'] == 'TEST_OBLIGATION'
                          and e['attributes']['coverageStatus'] != 'COVERED']
        for identifier, kind, status, rule, tests in candidates:
            if identifier in result:
                raise ValueError('Ambiguous static evidence identifier')
            result[identifier] = {'id': identifier, 'kind': kind, 'staticStatus': status, 'ruleId': rule,
                                  'location': location, 'tests': tests, 'severity': finding['severity'],
                                  'observation': finding.get('message', '')}
    return result


def excerpt(project, proof):
    path = safe_path(project, proof['path'])
    if digest(path) != proof['sha256'] or type(proof['line']) is not int or proof['line'] < 1:
        raise ValueError('Stale or invalid excerpt')
    lines = path.read_text().splitlines()
    if proof['line'] > len(lines) or not isinstance(proof['text'], str) or not proof['text'].strip() or lines[proof['line']-1].strip() != proof['text'].strip():
        raise ValueError('Excerpt does not match source')
    return path


def method_range(text, line, method):
    # Only ordinary Java void test methods are supported. Semantic sufficiency stays human-reviewed.
    if '"""' in text or type(line) is not int or line < 1:
        raise ValueError('Unsupported test source or method location')
    clean = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                   lambda match: ''.join('\n' if c == '\n' else ' ' for c in match.group()), text, flags=re.S)
    lines = clean.splitlines()
    if line > len(lines) or not re.search(r'\bvoid\s+' + re.escape(method) + r'\s*\(', lines[line-1]):
        raise ValueError('Assertion not bound to selected method')
    start = sum(len(s) + 1 for s in lines[:line-1])
    opening = clean.find('{', start)
    if opening < 0:
        raise ValueError('Test body missing')
    depth = 0
    for index in range(opening, len(clean)):
        depth += 1 if clean[index] == '{' else -1 if clean[index] == '}' else 0
        if depth == 0:
            return line, clean[:index].count('\n') + 1, lines
    raise ValueError('Test body incomplete')


def check_test(project, snapshot, proof):
    test = proof['test']
    classname, method = test.split('#')
    states = [state for name, state in snapshot['tests'].items() if name == test or name.startswith(test + '(')]
    if not states or any(state != 'PASSED' for state in states):
        raise ValueError('Test did not pass')
    path = safe_path(project, proof['path'])
    if snapshot['sources'].get(proof['path']) != proof['sha256'] or digest(path) != proof['sha256']:
        raise ValueError('Test source changed')
    text = path.read_text()
    if not re.search(r'\bclass\s+' + re.escape(classname.split('.')[-1]) + r'\b', text):
        raise ValueError('Wrong test class')
    package = classname.rpartition('.')[0]
    if package and not re.search(r'\bpackage\s+' + re.escape(package) + r'\s*;', text):
        raise ValueError('Wrong test package')
    start, end, clean = method_range(text, proof['methodLine'], method)
    if not isinstance(proof['assertions'], list) or not proof['assertions']:
        raise ValueError('Assertion evidence required')
    for assertion in proof['assertions']:
        excerpt(project, {**assertion, 'path': proof['path'], 'sha256': proof['sha256']})
        if not start <= assertion['line'] <= end or not re.search(r'\b(?:assert\w*|verify)\s*\(', clean[assertion['line']-1]):
            raise ValueError('Assertion belongs to another method or is not executable code')
    junit = proof['junit']
    if junit['file'] != 'TEST-' + classname + '.xml' or snapshot['junitSha256'].get(junit['file']) != junit['sha256']:
        raise ValueError('Execution report changed')


def review_evidence(project, snapshot, report_path, bundle, fixture_policy=None):
    project = project.resolve()
    if fixture_policy is not None and (not isinstance(fixture_policy, dict)
            or set(fixture_policy) != {'version', 'kind', 'project', 'allowedItems'}
            or fixture_policy['version'] != 1 or fixture_policy['kind'] != 'FIXTURE'
            or not isinstance(fixture_policy['project'], str) or not isinstance(fixture_policy['allowedItems'], list)
            or any(not isinstance(case, dict) or set(case) != {'id', 'sourceSha256', 'requirementSha256'}
                   or any(not isinstance(value, str) or not value.strip() for value in case.values())
                   for case in fixture_policy['allowedItems'])):
        raise ValueError('Invalid separately approved fixture policy')
    if snapshot.get('project') != str(project):
        raise ValueError('Review project differs from execution')
    report_file = safe_path(project, report_path)
    report = read_report(report_file)
    items = items_from_report(report)
    if not isinstance(bundle, dict) or set(bundle) != {'version', 'snapshotSha256', 'reportSha256', 'records'} or bundle['version'] != 1 or not isinstance(bundle['records'], list):
        raise ValueError('Invalid review bundle')
    records = {}
    for record in bundle['records']:
        if not isinstance(record, dict) or record.get('id') not in items or record['id'] in records:
            raise ValueError('Duplicate or unknown review target')
        if record.get('category') not in ('TEST_GAP', 'STATIC_LIMITATION', 'INTENTIONAL_DEFECT') or record.get('decision') not in ('VERIFIED', 'REPRODUCED', 'INSUFFICIENT'):
            raise ValueError('Invalid review classification or decision')
        records[record['id']] = record
    bound = bundle['snapshotSha256'] == fingerprint(snapshot) and bundle['reportSha256'] == digest(report_file)
    blocking = []
    for identifier, item in items.items():
        record = records.get(identifier)
        item.update(category=record['category'] if record else 'UNCLASSIFIED', accepted=False)
        item['review'] = record
        item['reason'] = 'Explicit review missing'
        if record:
            try:
                if not bound:
                    raise ValueError('Review belongs to stale execution or static report')
                if not all(isinstance(record.get(k), str) and record[k].strip() for k in ('reviewer', 'reason')):
                    raise ValueError('Reviewer and rationale required')
                source = safe_path(project, item['location']['file'])
                if snapshot['sources'].get(item['location']['file']) != record['sourceSha256'] or digest(source) != record['sourceSha256']:
                    raise ValueError('Production source changed')
                requirement = record['requirement']
                if not isinstance(requirement.get('id'), str) or not requirement['id'].strip():
                    raise ValueError('Requirement identifier required')
                excerpt(project, requirement)
                if record['category'] == 'TEST_GAP':
                    item['reason'] = 'Reviewer identified insufficient behavior coverage'
                else:
                    if not isinstance(record.get('tests'), list) or not record['tests']:
                        raise ValueError('Passing test and assertion evidence required')
                    for proof in record['tests']:
                        check_test(project, snapshot, proof)
                    if record['category'] == 'STATIC_LIMITATION':
                        if record['decision'] != 'VERIFIED':
                            raise ValueError('Behavior review is incomplete')
                        item['accepted'] = item['kind'] != 'PRODUCTION_RISK'
                        item['reason'] = 'Behavior reviewed; original static status preserved' if item['accepted'] else 'Production risk remains unresolved'
                    else:
                        allowed = (fixture_policy and fixture_policy.get('version') == 1 and fixture_policy.get('kind') == 'FIXTURE'
                                   and fixture_policy.get('project') == str(project)
                                   and any(case.get('id') == identifier and case.get('sourceSha256') == record['sourceSha256']
                                           and case.get('requirementSha256') == requirement['sha256']
                                           for case in fixture_policy.get('allowedItems', [])))
                        if record['decision'] != 'REPRODUCED' or not allowed:
                            raise ValueError('Separately approved fixture policy required for this exact defect')
                        item['accepted'] = True
                        item['reason'] = 'Intentional defect reproduced; implementation risk is preserved'
            except (ValueError, KeyError, TypeError, OSError, IndexError):
                item['reason'] = 'Review evidence invalid, incomplete or stale'
        if not item['accepted']:
            blocking.append(identifier)
    diagnostics = report.get('diagnostics', [])
    if not isinstance(diagnostics, list) or any(not isinstance(d, dict) for d in diagnostics):
        raise ValueError('Invalid analysis diagnostics')
    blocking += ['diagnostic:{}:{}'.format(index, d.get('code', 'UNKNOWN')) for index, d in enumerate(diagnostics)]
    return {'status': 'NEEDS_REVIEW' if blocking else 'PASSED', 'items': list(items.values()), 'blockingEvidence': blocking,
            'analysisDiagnostics': diagnostics,
            'targetRiskStatus': 'UNRESOLVED' if any(i['kind'] == 'PRODUCTION_RISK' for i in items.values()) else 'NO_REPORTED_RISKS',
            'categories': {category: sum(i['category'] == category for i in items.values())
                           for category in ('TEST_GAP', 'STATIC_LIMITATION', 'INTENTIONAL_DEFECT', 'UNCLASSIFIED')},
            'limitations': ['Review judgments are explicit caller evidence, not automatic semantic proof',
                            'Fixture policy authorizes reproduction only; it never declares risky code safe']}
