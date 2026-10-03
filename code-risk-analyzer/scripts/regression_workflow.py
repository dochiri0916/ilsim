#!/usr/bin/env python3
"""Explicit CODE_CHANGE regression workflow. No automatic impact or assertion proof."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

COMMAND = ['./gradlew', 'cleanTest', 'build', '--rerun-tasks', '--no-daemon']


def helper(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name+'.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


failure = helper('validation_failure')
quality = helper('quality_gate')


def parse_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Invalid JSON constant')))


def read_json(path):
    return parse_json(Path(path).read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_plan(plan):
    # Mode rejection precedes project inspection, hashing, Gradle and service execution.
    if not isinstance(plan, dict) or plan.get('mode') != 'CODE_CHANGE':
        raise ValueError('Regression execution requires CODE_CHANGE; forbidden in document/review modes')
    if set(plan) != {'mode', 'project', 'approvedActions', 'features', 'newFeatures', 'database'}:
        raise ValueError('Missing or unknown plan fields')
    if not isinstance(plan['project'], str) or not Path(plan['project']).is_absolute():
        raise ValueError('An absolute approved project is required')
    actions = plan['approvedActions']
    if not isinstance(actions, list) or len(actions) != len(set(actions)) or not set(actions) <= {'BASELINE', 'REGRESSION', 'LOCAL_SERVICE', 'COMPLETE', 'PULL_REQUEST'}:
        raise ValueError('Invalid approved actions')
    if not isinstance(plan['database'], str) or not plan['database'].strip():
        raise ValueError('Record actual database version or NOT_APPLICABLE')
    if not isinstance(plan['features'], dict) or not plan['features']:
        raise ValueError('Map affected existing features to tests')
    for feature, tests in plan['features'].items():
        if not isinstance(feature, str) or not feature.strip():
            raise ValueError('Feature requires an identifier')
        validate_tests(tests)
    if not isinstance(plan['newFeatures'], dict):
        raise ValueError('newFeatures must be an object')
    for feature, data in plan['newFeatures'].items():
        if not feature or not isinstance(data, dict) or set(data) != {'domain', 'mysql', 'mysqlRequired'} or type(data['mysqlRequired']) is not bool:
            raise ValueError('New features require domain/mysql tests and mysqlRequired')
        validate_tests(data['domain'])
        validate_tests(data['mysql'], allow_empty=not data['mysqlRequired'])
    return plan


def validate_tests(tests, allow_empty=False):
    if not isinstance(tests, list) or (not tests and not allow_empty) or any(not isinstance(t, str) or not re.fullmatch(r'[A-Za-z_$][\w.$]*#[A-Za-z_$][\w$]*', t) for t in tests) or len(tests) != len(set(tests)):
        raise ValueError('Tests must be distinct Class#method identifiers')


def source_hashes(project):
    result = {}
    for root in [project / 'src']:
        if root.is_symlink():
            raise ValueError('Symlink source roots forbidden')
        if root.exists():
            for path in sorted(root.rglob('*')):
                if path.is_symlink():
                    raise ValueError('Symlink sources forbidden')
                if path.is_file():
                    result[path.relative_to(project).as_posix()] = digest(path)
    for name in ('build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts',
                 'gradle.properties', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar',
                 'gradle/wrapper/gradle-wrapper.properties'):
        path = project / name
        if path.is_symlink() or any(parent.is_symlink() for parent in path.parents if parent != project.parent):
            raise ValueError('Symlink build inputs forbidden')
        if path.is_file():
            result[name] = digest(path)
    # Protect convention plugins and buildSrc as build/test configuration too.
    for name in ('buildSrc', 'build-logic', 'gradle', 'scripts'):
        root = project / name
        if root.is_symlink():
            raise ValueError('Symlink build inputs forbidden')
        if root.exists():
            for path in sorted(root.rglob('*')):
                if 'build' in path.relative_to(root).parts or '.gradle' in path.parts or '__pycache__' in path.parts:
                    continue
                if path.is_symlink():
                    raise ValueError('Symlink build inputs forbidden')
                if path.is_file():
                    result[path.relative_to(project).as_posix()] = digest(path)
    return result


def protected_sources(sources):
    return {path: value for path, value in sources.items()
            if not path.startswith('src/') or path.startswith('src/test/') or '/test/' in path or '/integrationTest/' in path}


def environment(project, database):
    java = subprocess.run(['java', '-version'], capture_output=True, text=True, timeout=15)
    if java.returncode:
        raise ValueError('Java version unavailable')
    version = '\n'.join(line for line in java.stderr.splitlines()
                        if line.startswith(('openjdk ', 'java version ', 'OpenJDK ', 'Java(TM) ')))
    if not version:
        raise ValueError('Java version not recognized')
    return {'java': version, 'os': platform.platform(), 'database': database,
            'gradleWrapper': digest(project / 'gradle/wrapper/gradle-wrapper.properties')}


def junit_results(directory):
    result = {}
    files = sorted(directory.glob('TEST-*.xml'))
    if not files:
        raise ValueError('Fresh JUnit XML is missing')
    for path in files:
        root = ET.parse(path).getroot()
        for case in root.iter('testcase'):
            # Preserve parameterized invocation names; simple JUnit method() is normalized.
            name = case.attrib.get('name', '')
            method = name[:-2] if name.endswith('()') else name
            identifier = case.attrib.get('classname', '') + '#' + method
            if not method or identifier in result:
                raise ValueError('Missing or ambiguous JUnit test identity')
            result[identifier] = ('FAILED' if case.find('failure') is not None or case.find('error') is not None
                                  else 'SKIPPED' if case.find('skipped') is not None else 'PASSED')
    if not result:
        raise ValueError('No tests executed')
    return result


def matching(tests, identifier):
    # Exact simple method or parameterized JUnit invocation; no class-name-only success.
    return [state for name, state in tests.items()
            if name == identifier or name.startswith(identifier + '(')]


def successful(tests, identifier):
    states = matching(tests, identifier)
    return bool(states) and all(state == 'PASSED' for state in states)


def compare(before, after, approvals=None, new_features=None):
    failures, questions, protected = [], [], []
    if before.get('version') != 1 or after.get('version') != 1:
        failures.append('Unsupported or missing snapshot version')
    for field in ('project', 'environment', 'command'):
        if before.get(field) != after.get(field):
            questions.append('Baseline differs: ' + field)
    def mapped_features(snapshot):
        return {feature: sorted(tests) for feature, tests in snapshot.get('features', {}).items()}
    if mapped_features(before) != mapped_features(after):
        questions.append('Baseline differs: features')
    for label, snapshot in [('baseline', before), ('after', after)]:
        if snapshot.get('buildExitCode') != 0 or not snapshot.get('tests'):
            failures.append(label + ' build/tests did not pass')
        for test, state in snapshot.get('tests', {}).items():
            if state != 'PASSED':
                failures.append(label + ': ' + test + ' ' + state)
    for feature, tests in before.get('features', {}).items():
        for test in tests:
            if not successful(before.get('tests', {}), test) or not successful(after.get('tests', {}), test):
                failures.append('Existing feature not verified: ' + feature + '/' + test)
    for test in before.get('tests', {}):
        if after.get('tests', {}).get(test) != 'PASSED':
            failures.append('Existing test missing or failed: ' + test)
    for path, old in before.get('protected', {}).items():
        new = after.get('protected', {}).get(path, 'ABSENT')
        if new == old:
            continue
        approved = any(a.get('path') == path and a.get('beforeSha256') == old and a.get('afterSha256') == new
                       and a.get('basis') in ('SPEC_CHANGE', 'INCORRECT_TEST')
                       and all(isinstance(a.get(k), str) and a[k].strip() for k in ('reference', 'reviewer', 'reason'))
                       for a in (approvals or []))
        protected.append({'path': path, 'beforeSha256': old, 'afterSha256': new, 'reviewed': approved})
        if not approved:
            questions.append('Test/build contract changed without hash-bound review: ' + path)
    for feature, data in (new_features or {}).items():
        if not data.get('domain') or data.get('mysqlRequired') and not data.get('mysql'):
            failures.append('Required test level missing: ' + feature)
        if data.get('mysqlRequired') and not after.get('environment', {}).get('database', '').startswith('MySQL '):
            questions.append('Actual MySQL evidence missing: ' + feature)
        for test in data.get('domain', []) + data.get('mysql', []):
            if not successful(after.get('tests', {}), test):
                failures.append('New feature not verified: ' + feature + '/' + test)
    return {'status': 'FAILED' if failures else 'NEEDS_REVIEW' if questions else 'PASSED',
            'failures': failures, 'openEvidence': questions, 'protectedChanges': protected,
            'limitations': ['Impact mapping, test-level classification, MySQL version and review rationale are explicit caller evidence; not automatically proven',
                            'Any protected-source change requires review; assertion equivalence is not statically proven']}


def completion(regression, snapshot, service, open_evidence, evidence_review=None):
    failures = list(regression.get('failures', []))
    questions = list(regression.get('openEvidence', []))
    if regression.get('status') not in ('PASSED', 'FAILED', 'NEEDS_REVIEW'):
        failures.append('Invalid regression result')
    if regression.get('status') == 'FAILED' and not failures:
        failures.append('Regression failed')
    if regression.get('status') == 'NEEDS_REVIEW' and not questions:
        questions.append('Regression requires review')
    if not service or service.get('status') != 'PASSED':
        failures.append('Separate local service verification missing or failed')
    if service:
        if service.get('sources') != snapshot.get('sources'):
            questions.append('Service was not verified on the final source snapshot')
        for scenario in ('normal', 'duplicate', 'concurrency', 'rollback'):
            if service.get('scenarios', {}).get(scenario) != 'PASSED':
                failures.append('Service scenario not passed: ' + scenario)
        if service.get('processStopped') is not True:
            failures.append('Service process cleanup not confirmed')
        if snapshot.get('artifactSha256') and service.get('jarSha256') not in snapshot['artifactSha256'].values():
            questions.append('Service artifact differs from the final build')
    verification_status = 'FAILED' if failures else 'NEEDS_REVIEW' if questions else 'PASSED'
    questions += list(open_evidence)
    if evidence_review:
        questions += ['Unresolved reviewed evidence: ' + identifier for identifier in evidence_review['blockingEvidence']]
        if evidence_review['status'] != 'PASSED' and not evidence_review['blockingEvidence']:
            questions.append('Evidence review incomplete')
    result = {'status': 'FAILED' if failures else 'NEEDS_REVIEW' if questions else 'COMPLETED',
            'verificationStatus': verification_status,
            'evidenceReviewStatus': evidence_review['status'] if evidence_review else 'NOT_PERFORMED',
            'targetRiskStatus': evidence_review['targetRiskStatus'] if evidence_review else 'NOT_ASSESSED',
            'failures': failures, 'openEvidence': questions,
            'meaning': 'Completion of the declared verification scope; not proof of all business behavior'}
    if evidence_review:
        result['evidenceReview'] = evidence_review
    return result


def review_module():
    import importlib.util
    spec = importlib.util.spec_from_file_location('evidence_review', Path(__file__).with_name('evidence_review.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verified_snapshot(project, path, plan):
    saved = read_json(path)
    saved = saved.get('snapshot', saved)
    if saved.get('version') != 1 or saved.get('project') != str(project) or saved.get('execution') != 'rerun' or saved.get('buildExitCode') != 0:
        raise ValueError('Final executed snapshot required')
    if saved.get('sources') != source_hashes(project) or saved.get('environment') != environment(project, plan['database']):
        raise ValueError('Final source/environment changed since execution')
    actual_junit = {p.name: digest(p) for p in sorted((project/'build/test-results/test').glob('TEST-*.xml'))}
    actual_jars = {p.name: digest(p) for p in sorted((project/'build/libs').glob('*.jar'))}
    if saved.get('junitSha256') != actual_junit or saved.get('artifactSha256') != actual_jars or saved.get('tests') != junit_results(project/'build/test-results/test'):
        raise ValueError('Final execution artifacts changed')
    return saved


def snapshot(project, plan, env=None, *, coverage=False, timeout_seconds=600):
    sources = source_hashes(project)
    recorded_environment = environment(project, plan['database'])
    # cleanTest + rerun avoids accepting stale or UP-TO-DATE execution evidence.
    recorded_command = COMMAND + (['codeRiskCoverage'] if coverage else [])
    execution_failure = None
    with tempfile.TemporaryDirectory(prefix='code-risk-coverage-') as directory, tempfile.TemporaryFile() as log:
        command = list(recorded_command)
        if coverage:
            init = Path(directory)/'coverage.init.gradle'
            init.write_text(quality.COVERAGE_INIT)
            command += ['--init-script', str(init)]
            for path in (quality.COVERAGE_XML, quality.COVERAGE_EXEC):
                target = project/path
                if any(parent.is_symlink() for parent in [target, *target.parents]):
                    raise ValueError('Symlink coverage output forbidden')
                target.unlink(missing_ok=True)
        for target in (project/'build/test-results/test').glob('TEST-*.xml'):
            if any(parent.is_symlink() for parent in [target, *target.parents]):
                raise ValueError('Symlink test output forbidden')
            target.unlink()
        process = subprocess.Popen(command, cwd=project, env=env, stdout=log,
                                   stderr=log, start_new_session=True)
        try:
            code = process.wait(timeout=timeout_seconds)
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            raise
        log.seek(max(0, log.tell()-2*1024*1024))
        if code:
            execution_failure = failure.gradle_failure(log.read().decode('utf-8', errors='replace'), project)
    result = {'version': 1, 'project': str(project), 'features': plan['features'], 'sources': sources,
              'protected': protected_sources(sources), 'environment': recorded_environment, 'command': recorded_command,
              'buildExitCode': code, 'tests': {}, 'recordedAt': time.time(), 'execution': 'rerun'}
    try:
        result['tests'] = junit_results(project / 'build/test-results/test')
        result['testFailures'] = failure.junit_failures(project/'build/test-results/test', project)
    except (ValueError, ET.ParseError, OSError):
        result['buildExitCode'] = code or 1
        result['evidenceError'] = 'No unambiguous fresh JUnit results'
    result['observedSources'] = source_hashes(project)
    if result['observedSources'] != sources:
        result['buildExitCode'] = code or 1
        result['evidenceError'] = 'Source changed during execution'
    result['junitSha256'] = {p.name: digest(p) for p in sorted((project / 'build/test-results/test').glob('TEST-*.xml'))}
    result['artifactSha256'] = {p.name: digest(p) for p in sorted((project / 'build/libs').glob('*.jar'))}
    if execution_failure:
        result['executionFailure'] = execution_failure
    if coverage:
        result['coverageInitSha256'] = hashlib.sha256(quality.COVERAGE_INIT.encode()).hexdigest()
        result['coverageArtifacts'] = {name: digest(quality.safe_file(project, name)) for name in (quality.COVERAGE_XML, quality.COVERAGE_EXEC)
                                       if (project/name).is_file()}
    return result


def save(path, data, *, exclusive=False):
    path = Path(path)
    # Never overwrite baseline silently or write through a symlink.
    if path.is_symlink():
        raise ValueError('Symlink output forbidden')
    path.parent.mkdir(parents=True, exist_ok=True)
    if exclusive:
        with path.open('x') as stream:
            stream.write(json.dumps(data, indent=2) + '\n')
    else:
        with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json.dumps(data, indent=2) + '\n')
        temporary.replace(path)


def validate_requirements(path, analyzer):
    # Check mode before even starting the validator process; it runs document action only.
    requirements = read_json(path)
    if requirements.get('mode') != 'CODE_CHANGE':
        raise ValueError('Requirements must use CODE_CHANGE')
    result = subprocess.run(['java', '-jar', str(analyzer), 'requirements', str(path),
                             '--actions', 'DOCUMENT_ANALYSIS'], capture_output=True, timeout=60)
    if result.returncode:
        raise ValueError('Requirements rejected by JSON Schema validator')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=('baseline', 'compare', 'complete'))
    for name in ('plan', 'requirements', 'analyzer', 'output'):
        parser.add_argument('--' + name, required=True)
    for name in ('baseline', 'approvals', 'service', 'open-evidence', 'static-report', 'evidence-review', 'fixture-policy', 'verified-snapshot'):
        parser.add_argument('--' + name)
    args = parser.parse_args()
    try:
        plan = validate_plan(read_json(args.plan))
        action = {'baseline': 'BASELINE', 'compare': 'REGRESSION', 'complete': 'COMPLETE'}[args.stage]
        if action not in plan['approvedActions']:
            raise ValueError('Stage outside approved actions')
        if args.stage != 'baseline' and not args.baseline:
            raise ValueError('Original baseline required')
        if args.stage == 'complete' and (not {'LOCAL_SERVICE', 'REGRESSION'} <= set(plan['approvedActions']) or not args.service or not args.open_evidence):
            raise ValueError('Completion requires approved local service evidence and explicit open-evidence list')
        reviewed = any((args.static_report, args.evidence_review, args.fixture_policy, args.verified_snapshot))
        if reviewed and (args.stage != 'complete' or not all((args.static_report, args.evidence_review, args.verified_snapshot))):
            raise ValueError('Reviewed completion requires static report, review and final executed snapshot')
        validate_requirements(Path(args.requirements).resolve(), Path(args.analyzer).resolve())
        project = Path(plan['project'])
        if project.is_symlink() or project.resolve() != project or not (project / 'gradlew').is_file():
            raise ValueError('Canonical local Gradle project required')
        if project / 'gradlew' in (Path(args.output),):
            raise ValueError('Output cannot replace build inputs')
        output = Path(args.output).resolve()
        if output.is_relative_to(project) and not output.is_relative_to(project / 'build'):
            raise ValueError('Project artifacts must be under build/')
        if args.baseline and output == Path(args.baseline).resolve():
            raise ValueError('Original baseline cannot be overwritten')
        before = read_json(args.baseline) if args.baseline else None
        approvals = read_json(args.approvals) if args.approvals else []
        if not isinstance(approvals, list) or any(not isinstance(a, dict) for a in approvals):
            raise ValueError('Approvals must be an array of review records')
        open_evidence = read_json(args.open_evidence) if args.open_evidence else []
        if not isinstance(open_evidence, list) or any(not isinstance(q, str) or not q.strip() for q in open_evidence):
            raise ValueError('open-evidence must be an explicit string array')
        token = hashlib.sha256(str(project).encode()).hexdigest()
        lock_path = Path(tempfile.gettempdir()) / ('code-risk-regression-' + token + '.lock')
        with os.fdopen(os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600), 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if args.stage == 'baseline' and output.exists():
                raise ValueError('Baseline already exists')
            after = verified_snapshot(project, args.verified_snapshot, plan) if reviewed else snapshot(project, plan)
            if args.stage == 'baseline':
                result = {'snapshot': after, 'regression': compare(after, after)}
                save(output, after, exclusive=True)
            else:
                regression = compare(before, after, approvals, plan['newFeatures'])
                result = {'snapshot': after, 'regression': regression}
                if args.stage == 'complete':
                    evidence = None
                    if reviewed:
                        policy = read_json(args.fixture_policy) if args.fixture_policy else None
                        if policy and isinstance(policy.get('project'), str):
                            policy['project'] = str((Path(args.fixture_policy).resolve().parent / policy['project']).resolve())
                        evidence = review_module().review_evidence(project, after, args.static_report, read_json(args.evidence_review), policy)
                    result['completion'] = completion(regression, after, read_json(args.service), open_evidence, evidence)
                save(output, result)
            status = result.get('completion', result['regression'])['status']
            print(json.dumps({'status': status, 'output': str(output)}))
            return 1 if status == 'FAILED' else 3 if status == 'NEEDS_REVIEW' else 0
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # Never echo source text, credentials, or target build output.
        print('Regression workflow rejected or failed; inspect approved metadata and build directly')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
