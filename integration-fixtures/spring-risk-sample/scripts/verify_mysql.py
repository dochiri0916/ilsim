#!/usr/bin/env python3
"""Run isolated MySQL tests and verify reviewed execution-evidence import."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
import argparse
from verify_service import verify_service, workflow
from fixture_reviews import build_reviews

PROJECT = Path(__file__).resolve().parents[1]
REPO = PROJECT.parents[1]
BUILD = PROJECT / 'build'
IMAGE = 'mysql:8.4.8'
TEST = 'com.example.risksample.DatabaseBehaviorTests'


def run(args, *, cwd=REPO, env=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def obligations(report):
    return {e['attributes']['obligationId']: (f, e['attributes'])
            for f in report['findings'] for e in f['evidence']
            if e['type'] == 'TEST_OBLIGATION'}


def production_findings(report):
    return [f for f in report['findings'] if f['ruleId'] != 'TEST-001']


def scan(jar, name, manifest=None):
    args = ['java', '-jar', jar, 'scan', PROJECT, '--format', 'json',
            '--output', f'build/{name}.json', '--fail-on', 'off']
    if manifest:
        args += ['--execution-evidence', f'build/{manifest}.json']
    run(args)
    return json.loads((BUILD / f'{name}.json').read_text())


def verify_evidence(jar, version):
    static = scan(jar, 'scan-static')
    selected = obligations(static)
    definitions = [
        ('UnsafeService#createIfAbsent', 'RISK', 'CON-001',
         'concurrentUnsafeCreatesCommitDuplicateRows', 'CONCURRENCY',
         'Two real MySQL exists queries return absent at a CyclicBarrier before inserts; both futures succeed and committed row count is 2. This reproduces the unsafe defect, not safe handling.'),
        ('SafeService#saveThenFail', 'TRANSACTION_ROLLBACK', '',
         'proxyInvocationRollsBackTheWrite', 'ROLLBACK',
         'Call through the Spring service proxy, assert IllegalStateException and zero rows using a subsequent repository query outside the failed transaction.'),
        ('UnsafeService#saveThenFail', 'TRANSACTION_ROLLBACK', '',
         'directProxyInvocationOfUnsafeServiceStillRollsBack', 'ROLLBACK',
         'Direct service proxy invocation rolls back; exception and zero committed rows are asserted without a test transaction.'),
        ('UnsafeService#selfInvokeThenFail', 'RISK', 'TX-001',
         'selfInvocationLeavesTheRepositoryCommitBehind', 'ROLLBACK',
         'Self invocation bypasses the service transaction; the repository commit remains. Exception plus one committed row is asserted. This is intentionally unsafe.'),
        ('UnsafeService#handleMessage', 'PERSISTENCE', '',
         'repeatedUnsafeMessageProducesTwoWrites', 'DUPLICATE_MESSAGE',
         'Repeated calls with the same message ID leave two committed rows; no real broker or acknowledgment is modeled.'),
    ]
    entries = []
    for method, kind, risk, test_method, scenario, reason in definitions:
        matches = [(f, a) for f, a in selected.values()
                   if f['message'].split(' | ')[0].endswith(method)
                   and a['type'] == kind and a['riskId'] == risk]
        if len(matches) != 1:
            raise RuntimeError(f'Expected one obligation for {method}/{kind}/{risk}')
        finding, attrs = matches[0]
        test_name = f'{TEST}#{test_method}'
        if test_name not in attrs['tests']:
            raise RuntimeError(f'Test not statically linked: {test_name}')
        required = next(line.removeprefix('Required: ') for line in finding['message'].splitlines()
                        if line.startswith('Required: '))
        source = PROJECT / finding['location']['file']
        test_source = PROJECT / 'src/test/java/com/example/risksample/DatabaseBehaviorTests.java'
        junit = Path(f'build/test-results/test/TEST-{TEST}.xml')
        jacoco = Path('build/reports/jacoco/test/jacocoTestReport.xml')
        entries.append({
            'obligationId': attrs['obligationId'], 'test': test_name, 'scenario': scenario,
            'database': f'MySQL {version} / isolated Docker {IMAGE}',
            'databaseEvidence': reason,
            'sourceSha256': digest(source), 'testSourceSha256': digest(test_source),
            'junit': str(junit), 'junitSha256': digest(PROJECT / junit),
            'jacoco': str(jacoco), 'jacocoSha256': digest(PROJECT / jacoco),
            'assertionReview': {'reviewer': 'Codex / explicit sample assertion review',
                                'reason': reason, 'requiredEvidence': required},
        })
    (BUILD / 'execution-evidence.json').write_text(json.dumps({'version': 1, 'entries': entries}, indent=2) + '\n')
    linked = scan(jar, 'scan-reviewed', 'execution-evidence')
    if production_findings(static) != production_findings(linked):
        raise RuntimeError('Production findings changed during evidence import')
    linked_items = obligations(linked)
    changes = []
    for entry in entries:
        oid = entry['obligationId']
        original, attrs = selected[oid]
        finding, linked_attrs = linked_items[oid]
        imported = [e['attributes'] for e in finding['evidence'] if e['type'] == 'EXECUTION_TEST']
        if len(imported) != 1:
            raise RuntimeError(f'Missing execution evidence: {oid}')
        evidence = imported[0]
        if not evidence['testPassed']:
            raise RuntimeError(f'Execution not verified: {oid}')
        # The self-invocation always throws; its implicit return is unreachable and
        # JaCoCo reports missed instructions on that line. Preserve this limitation.
        expected_coverage = entry['test'] != f'{TEST}#selfInvocationLeavesTheRepositoryCommitBehind'
        if evidence['methodLinesCovered'] != expected_coverage:
            raise RuntimeError(f'Unexpected method coverage observation: {oid}')
        expected_acceptance = attrs['type'] == 'TRANSACTION_ROLLBACK' or entry['scenario'] == 'CONCURRENCY'
        if expected_acceptance and attrs['coverageStatus'] != 'PARTIAL':
            raise RuntimeError(f'Expected PARTIAL before reviewed evidence: {oid}')
        expected_status = 'COVERED' if expected_acceptance else attrs['coverageStatus']
        if evidence['acceptedForObligation'] != expected_acceptance or linked_attrs['coverageStatus'] != expected_status:
            raise RuntimeError(f'Unexpected promotion: {oid}')
        if evidence['databaseVerifiedByAnalyzer']:
            raise RuntimeError('Analyzer must not claim to verify the database')
        changes.append({'obligationId': oid, 'method': original['message'].split(' | ')[0],
                        'type': attrs['type'], 'before': attrs['coverageStatus'],
                        'after': linked_attrs['coverageStatus'], 'accepted': expected_acceptance,
                        'methodLinesCovered': evidence['methodLinesCovered']})
    for oid, (_, attrs) in selected.items():
        if oid not in {e['obligationId'] for e in entries} and linked_items[oid][1]['coverageStatus'] != attrs['coverageStatus']:
            raise RuntimeError(f'Unreviewed obligation changed: {oid}')
    # Coverage plus execution alone must not promote a risk obligation.
    unreviewed = [{k: v for k, v in entry.items() if k != 'assertionReview'} for entry in entries]
    (BUILD / 'execution-evidence-unreviewed.json').write_text(json.dumps({'version': 1, 'entries': unreviewed}, indent=2) + '\n')
    coverage_only = scan(jar, 'scan-unreviewed', 'execution-evidence-unreviewed')
    if production_findings(coverage_only) != production_findings(static):
        raise RuntimeError('Unreviewed import changed production findings')
    for oid, (_, attrs) in selected.items():
        if obligations(coverage_only)[oid][1]['coverageStatus'] != attrs['coverageStatus']:
            raise RuntimeError(f'Coverage-only import promoted: {oid}')
    summary = {'database': f'MySQL {version}', 'changes': changes,
               'productionFindingsUnchanged': True, 'unreviewedStatusesUnchanged': True,
               'remainingTestEvidence': [
                   {'obligationId': a['obligationId'], 'status': a['coverageStatus'],
                    'severity': f['severity'], 'method': f['message'].split(' | ')[0], 'tests': a['tests']}
                   for f, a in linked_items.values() if a['coverageStatus'] != 'COVERED'],
               'remainingProductionRisks': [
                   {'ruleId': f['ruleId'], 'status': f['status'], 'severity': f['severity'], 'location': f['location']}
                   for f in production_findings(linked)],
               'analysisDiagnostics': linked.get('diagnostics', []),
               'remainingHighGaps': [a['obligationId'] for f, a in linked_items.values()
                                     if f['severity'] in ('HIGH', 'CRITICAL') and a['coverageStatus'] != 'COVERED'],
               'limitations': ['TX-001 and PERSISTENCE are not supported for evidence promotion',
                               'The always-throwing self-invocation leaves an unreachable implicit return reported as missed instructions',
                               'Coverage is aggregate, not per test; DB/assertion evidence is reviewer-attested',
                               'Single MySQL instance; no real broker or external side effects']}
    (BUILD / 'verification-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', default='CODE_CHANGE')
    parser.add_argument('--baseline', help='Previously recorded pre-change snapshot; never overwritten')
    args = parser.parse_args()
    if args.mode != 'CODE_CHANGE':
        print('MySQL/service regression execution is forbidden outside CODE_CHANGE')
        return 2
    before = workflow.read_json(args.baseline) if args.baseline else None
    container = f'code-risk-sample-{uuid.uuid4().hex[:12]}'
    final = {'status': 'FAILED', 'failures': ['Verification did not finish'], 'openEvidence': []}
    completion_status = None
    stage = 'MYSQL_READY'
    with tempfile.TemporaryDirectory(prefix='code-risk-mysql-') as temporary:
        password = secrets.token_urlsafe(32)
        env_file = Path(temporary) / 'mysql.env'
        env_file.write_text(f'MYSQL_RANDOM_ROOT_PASSWORD=yes\nMYSQL_DATABASE=risk_sample\nMYSQL_USER=risk_sample\nMYSQL_PASSWORD={password}\n')
        env_file.chmod(0o600)
        try:
            run(['docker', 'run', '--detach', '--rm', '--name', container,
                 '--label', 'code-risk-analyzer.fixture=spring-risk-sample',
                 '--env-file', env_file, '--publish', '127.0.0.1::3306', IMAGE])
            deadline = time.monotonic() + 120
            while True:
                ready = subprocess.run(['docker', 'exec', container, 'sh', '-c',
                    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -N -e "SELECT VERSION()"'],
                    capture_output=True, text=True)
                if ready.returncode == 0:
                    version = ready.stdout.strip()
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError('Dedicated MySQL did not become ready within 120 seconds')
                time.sleep(1)
            port = subprocess.check_output(['docker', 'port', container, '3306/tcp'], text=True).strip().rsplit(':', 1)[1]
            env = dict(os.environ, RISK_SAMPLE_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{port}/risk_sample?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=30000',
                       RISK_SAMPLE_MYSQL_USER='risk_sample', RISK_SAMPLE_MYSQL_PASSWORD=password)
            print(f'Running tests on isolated MySQL {version}', flush=True)
            plan = workflow.validate_plan({
                'mode': 'CODE_CHANGE', 'project': str(PROJECT),
                'approvedActions': ['BASELINE', 'REGRESSION', 'LOCAL_SERVICE', 'COMPLETE'],
                'database': f'MySQL {version}',
                'features': {
                    'domain.discount': [
                        'com.example.risksample.PricePolicyTests#rejectsNonPositiveQuantities',
                        'com.example.risksample.PricePolicyTests#appliesDiscountAtTheExactBoundary'],
                    'database.existing-behaviors': [f'{TEST}#{name}' for name in [
                        'proxyInvocationRollsBackTheWrite', 'selfInvocationLeavesTheRepositoryCommitBehind',
                        'directProxyInvocationOfUnsafeServiceStillRollsBack', 'repeatedUnsafeMessageProducesTwoWrites',
                        'repeatedSafeMessageReportsConflictAndKeepsOneWrite', 'existingUnsafeRecordIsNotInsertedAgain',
                        'concurrentUnsafeCreatesCommitDuplicateRows', 'concurrentCreatesHaveOneCommitAndOneConflict']]},
                'newFeatures': {'local.entrypoint': {
                    'domain': ['com.example.risksample.PricePolicyTests#appliesDiscountAtTheExactBoundary'],
                    'mysql': [f'com.example.risksample.LocalServiceEndpointTests#{name}' for name in [
                        'normalAndDuplicateRequestsLeaveOneCommittedRow', 'failedRequestRollsBackAndCanBeRetried',
                        'concurrentRequestsReportOneCommitAndOneConflict', 'invalidInputAndUnsupportedMethodsDoNotWrite',
                        'startsIdempotentlyStopsAndRestartsLocalServer']],
                    'mysqlRequired': True}}})
            if before is None:
                stage = 'BASELINE'
                before = workflow.snapshot(PROJECT, plan, env)
                workflow.save(BUILD / 'regression-baseline.json', before)
                baseline_check = workflow.compare(before, before)
                if baseline_check['status'] != 'PASSED':
                    final['failures'] = baseline_check['failures']
                    raise RuntimeError('Baseline not healthy')
            stage = 'REGRESSION'
            run([PROJECT / 'gradlew', 'test', '--tests', f'{TEST}.concurrentUnsafeCreatesCommitDuplicateRows', '--rerun-tasks', '--no-daemon'], cwd=PROJECT, env=env)
            after = workflow.snapshot(PROJECT, plan, env)
            regression = workflow.compare(before, after, new_features=plan['newFeatures'])
            workflow.save(BUILD / 'regression-comparison.json', {'snapshot': after, 'regression': regression})
            xml_files = list((BUILD / 'test-results/test').glob('TEST-*.xml'))
            totals = {key: sum(int(ET.parse(path).getroot().get(key, '0')) for path in xml_files)
                      for key in ('tests', 'failures', 'errors', 'skipped')}
            if totals != {'tests': 17, 'failures': 0, 'errors': 0, 'skipped': 0}:
                raise RuntimeError(f'Unexpected test totals: {totals}')
            run([REPO / 'gradlew', 'build', '--no-daemon'])
            jar = REPO / 'build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar'
            stage = 'STATIC'
            verify_evidence(jar, version)
            stage = 'LOCAL_SERVICE'
            service = verify_service(env, container)
            evidence = json.loads((BUILD / 'verification-summary.json').read_text())
            stage = 'EVIDENCE_REVIEW'
            classified = build_reviews(after)
            open_evidence = ['Static diagnostic: ' + entry.get('code', 'UNKNOWN')
                             for entry in evidence['analysisDiagnostics']]
            final = workflow.completion(regression, after, service, open_evidence, classified)
            final.update({'regression': regression, 'serviceEvidence': 'service-verification.json',
                          'staticEvidence': 'verification-summary.json'})
            workflow.save(BUILD / 'workflow-completion.json', final)
            completion_status = final['status']
        except Exception:
            final['status'] = 'FAILED'
            final['failures'].append('Verification failed at stage: ' + stage)
        finally:
            # Only this run's generated container name is removed; existing DBs are untouched.
            subprocess.run(['docker', 'rm', '--force', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            inspected = subprocess.run(['docker', 'inspect', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if final is not None:
                final['mysqlContainerStopped'] = inspected.returncode != 0
                if not final['mysqlContainerStopped']:
                    final['status'] = 'FAILED'
                    final['verificationStatus'] = 'FAILED'
                    final['failures'].append('Dedicated MySQL cleanup failed')
                workflow.save(BUILD / 'workflow-completion.json', final)
                completion_status = final['status']
    print(json.dumps({'completion': completion_status, 'report': str(BUILD / 'workflow-completion.json')}), flush=True)
    return 1 if completion_status == 'FAILED' else 3 if completion_status == 'NEEDS_REVIEW' else 0


if __name__ == '__main__':
    raise SystemExit(main())
