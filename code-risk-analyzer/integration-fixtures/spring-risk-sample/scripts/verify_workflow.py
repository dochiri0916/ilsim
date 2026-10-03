#!/usr/bin/env python3
"""Real CLI session and failure/retry checks on the owned MySQL fixture."""
import argparse
import json
from contextlib import contextmanager
import os
from pathlib import Path
import secrets
import signal
import subprocess
import tempfile
import time
import uuid
from fixture_reviews import build_reviews
from verify_mysql import IMAGE, PROJECT, REPO, TEST, workflow

CONTROLLER = REPO/'scripts/code_change_workflow.py'


@contextmanager
def mysql(summary):
    container = 'code-risk-sample-' + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix='code-risk-workflow-') as directory:
        password = secrets.token_urlsafe(32)
        env_file = Path(directory)/'mysql.env'
        env_file.write_text(f'MYSQL_RANDOM_ROOT_PASSWORD=yes\nMYSQL_DATABASE=risk_sample\nMYSQL_USER=risk_sample\nMYSQL_PASSWORD={password}\n')
        env_file.chmod(0o600)
        try:
            subprocess.run(['docker', 'run', '--detach', '--rm', '--name', container,
                '--label', 'code-risk-analyzer.fixture=spring-risk-sample', '--env-file', str(env_file),
                '--publish', '127.0.0.1::3306', IMAGE], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
            deadline = time.monotonic()+120
            while True:
                ready = subprocess.run(['docker', 'exec', container, 'sh', '-c',
                    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -N -e "SELECT VERSION()"'],
                    capture_output=True, text=True, timeout=15)
                if ready.returncode == 0:
                    version = ready.stdout.strip()
                    break
                if time.monotonic() >= deadline:
                    raise ValueError('Dedicated MySQL readiness failed')
                time.sleep(1)
            port = subprocess.check_output(['docker', 'port', container, '3306/tcp'], text=True, timeout=15).strip().rsplit(':', 1)[1]
            env = dict(os.environ, RISK_SAMPLE_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{port}/risk_sample?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=30000',
                       RISK_SAMPLE_MYSQL_USER='risk_sample', RISK_SAMPLE_MYSQL_PASSWORD=password)
            yield container, version, env
        finally:
            subprocess.run(['docker', 'rm', '--force', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            check = subprocess.run(['docker', 'inspect', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
            summary['mysqlContainerStopped'] = check.returncode != 0
            if not summary['mysqlContainerStopped']:
                raise ValueError('Owned MySQL cleanup failed')


def metadata(directory, version, analyzer):
    # The contracts are fixture contracts; no unrelated example is asserted as implemented.
    requirement = {
        'title': '기존 가격 정책 보존과 단계 실행 연결',
        'source': {'document': 'review-requirements.md', 'version': workflow.digest(PROJECT/'review-requirements.md'),
                   'section': 'REQ-PRICE', 'excerpt': '수량 1 미만은 IllegalArgumentException, 수량 1~9는 할인 0%, 10 이상은 10%다.'},
        'domain': {'owner': 'PricePolicy', 'aggregates': ['가격 정책'], 'dataOwnership': ['상태 없는 계산'],
                   'allowedDependencies': ['외부 부수 효과 없음'], 'entryPoints': ['discountPercent']},
        'preconditions': ['수량 입력'], 'postconditions': ['정의된 할인율 또는 입력 오류'],
        'invariants': ['기존 수량 경계 계약 유지'], 'coreLogic': ['수량 검증 및 할인율 계산'],
        'scenarios': {'SCN-PRICE': {'type': 'NORMAL', 'given': '수량 9와 10', 'when': '할인율 계산',
                       'expectedOutcomes': ['각각 0과 10'], 'testTypes': ['DOMAIN_UNIT']}},
        'review': {'status': 'INSUFFICIENT_EVIDENCE', 'reason': '실행 결과는 단계 실행 이후 별도 연결한다.',
                   'evidence': [{'kind': 'REQUIREMENT', 'path': 'review-requirements.md', 'locator': 'REQ-PRICE',
                                 'observation': '기존 정책을 그대로 보존하는 연결 검증이다.'}], 'openQuestions': ['최종 실행 및 서비스 근거 연결 여부']},
        'mappings': {'implementationStatus': 'UNKNOWN', 'code': [], 'tests': [], 'findings': []}}
    for name in ('transaction', 'concurrency', 'idempotency', 'consistency'):
        requirement[name] = {'applicability': 'NOT_APPLICABLE', 'reason': '가격 계산은 상태 없는 순수 계산이며 외부 부수 효과가 없다.',
                             'expectations': []}
    requirements = {'version': 1, 'mode': 'CODE_CHANGE', 'project': {'name': 'spring-risk-sample', 'database': 'MYSQL'},
                    'requirements': {'REQ-PRICE': requirement}, 'codeRevision': 'SOURCE_HASH_BOUND_SESSION'}
    plan = {'mode': 'CODE_CHANGE', 'project': str(PROJECT),
            'approvedActions': ['BASELINE', 'REGRESSION', 'LOCAL_SERVICE', 'COMPLETE'], 'database': 'MySQL '+version,
            'features': {'all-existing': [
                'com.example.risksample.PricePolicyTests#rejectsNonPositiveQuantities',
                'com.example.risksample.PricePolicyTests#appliesDiscountAtTheExactBoundary',
                TEST+'#concurrentCreatesHaveOneCommitAndOneConflict', TEST+'#proxyInvocationRollsBackTheWrite']},
            'newFeatures': {}}
    files = ['src/test/java/com/example/risksample/PricePolicyTests.java', 'src/main/java/com/example/risksample/PricePolicy.java']
    scope = {'project': str(PROJECT), 'files': files, 'actions': ['WRITE_TESTS', 'IMPLEMENT']}
    for name, value in [('requirements', requirements), ('plan', plan), ('scope', scope)]:
        workflow.save(directory/(name+'.json'), value)
    config = {name: str(directory/(name+'.json')) for name in ('requirements', 'plan', 'scope')}
    config.update({'analyzer': str(analyzer), 'documents': ['review-requirements.md', 'review-definitions.json', 'fixture-review-policy.json'], 'fixtureAdapter': 'MYSQL'})
    workflow.save(directory/'config.json', config)
    # Deliberately identical content: exercise real writer and phase guards without inventing a feature.
    for stage, path in zip(('test-writing', 'implementation'), files):
        source = PROJECT/path
        workflow.save(directory/(stage+'.json'), {'changes': [{'path': path, 'expectedSha256': workflow.digest(source), 'content': source.read_text()}]})
    workflow.save(directory/'open.json', [])


class Session:
    def __init__(self, directory, env):
        self.directory, self.env = directory, env

    def launch(self, stage, *options, env=None):
        command = ['python3', str(CONTROLLER), stage, '--config', str(self.directory/'config.json'),
                   '--state', str(self.directory/'state.json'), *map(str, options)]
        return subprocess.Popen(command, cwd=REPO, env=env or self.env, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, start_new_session=True)

    def finish(self, process, expected=0):
        try:
            process.communicate(timeout=600)
        except BaseException:
            self.stop(process)
            raise
        if process.returncode != expected:
            raise ValueError('Unexpected CLI result')

    def run(self, stage, *options, expected=0, env=None):
        print('CLI stage: '+stage, flush=True)
        self.finish(self.launch(stage, *options, env=env), expected)

    def stop(self, process):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.communicate(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=10)

    def running(self, process):
        deadline = time.monotonic()+30
        while time.monotonic() < deadline:
            progress = self.directory/'service-running.json'
            if progress.exists():
                return workflow.read_json(progress)['processId']
            if process.poll() is not None:
                raise ValueError('Service stage exited before JVM launch')
            time.sleep(0.05)
        raise ValueError('No owned JVM startup observation')

    def stopped(self):
        result = workflow.read_json(PROJECT/'build/service-verification.json')
        if result['processStopped'] is not True:
            raise ValueError('JVM cleanup not recorded')
        try:
            os.kill(result['processId'], 0)
        except ProcessLookupError:
            return
        raise ValueError('Owned JVM still exists')

    def failures(self, container):
        evidence = []
        source = PROJECT/'src/main/java/com/example/risksample/PricePolicy.java'
        original = source.read_text()
        mutated = original.replace('quantity >= 10 ? 10 : 0', 'quantity >= 10 ? 11 : 0')
        if mutated == original:
            raise ValueError('Expected price policy mutation target missing')
        self.run('test-writing', '--changes', self.directory/'test-writing.json')
        workflow.save(self.directory/'failed-implementation.json', {'changes': [{'path': source.relative_to(PROJECT).as_posix(),
            'expectedSha256': workflow.digest(source), 'content': mutated}]})
        try:
            self.run('implementation', '--changes', self.directory/'failed-implementation.json')
            self.run('verification', expected=1)
            regression = workflow.read_json(self.directory/'regression.json')
            if regression['status'] != 'FAILED' or not any('appliesDiscountAtTheExactBoundary' in failure for failure in regression['failures']):
                raise ValueError('Actual price assertion failure was not observed')
            workflow.save(self.directory/'failed-regression.json', regression)
            self.run('local-service', '--container', container, expected=2)
            self.run('complete', expected=2)
        finally:
            current = source.read_text()
            if current == mutated:
                try:
                    self.run('test-writing', '--changes', self.directory/'test-writing.json')
                    workflow.save(self.directory/'restore-implementation.json', {'changes': [{'path': source.relative_to(PROJECT).as_posix(),
                        'expectedSha256': workflow.digest(source), 'content': original}]})
                    self.run('implementation', '--changes', self.directory/'restore-implementation.json')
                finally:
                    # Interrupted controller may have left WRITING; restore only our exact content.
                    if source.read_text() == mutated:
                        source.write_text(original)
            elif current != original:
                raise ValueError('Concurrent edit preserved; explicit price review required')
        evidence.append({'scenario': 'test-regression', 'status': 'PASSED', 'completionBlocked': True})
        self.run('verification')
        invalid_env = dict(self.env, SPRING_JPA_DATABASE_PLATFORM='org.hibernate.dialect.MissingFixtureDialect')
        self.run('local-service', '--container', container, expected=1, env=invalid_env)
        self.stopped(); self.run('complete', expected=2)
        evidence.append({'scenario': 'startup-failure', 'status': 'PASSED', 'processStopped': True})
        process = self.launch('local-service', '--container', container)
        try:
            self.running(process)
            os.killpg(process.pid, signal.SIGTERM)
            self.finish(process, expected=130)
        finally:
            self.stop(process)
        self.stopped(); self.run('complete', expected=2)
        evidence.append({'scenario': 'interrupt', 'status': 'PASSED', 'processStopped': True})
        source = PROJECT/'src/main/java/com/example/risksample/PricePolicy.java'
        original = source.read_bytes()
        changed = original+b'\n// Temporary source drift verification\n'
        process = self.launch('local-service', '--container', container)
        try:
            self.running(process)
            if source.read_bytes() != original:
                raise ValueError('Concurrent source change before fault injection')
            source.write_bytes(changed)
            self.finish(process, expected=2)
            self.stopped()
            if workflow.read_json(self.directory/'state.json')['phase'] != 'STALE':
                raise ValueError('Source drift was not invalidated')
        finally:
            self.stop(process)
            current = source.read_bytes()
            if current == changed:
                source.write_bytes(original)
            elif current != original:
                raise ValueError('Concurrent edit preserved; explicit source review required')
        self.run('complete', expected=2)
        evidence.append({'scenario': 'source-drift', 'status': 'PASSED', 'processStopped': True})
        workflow.save(self.directory/'failure-checks.json', evidence)
        return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', default='CODE_CHANGE')
    args = parser.parse_args()
    if args.mode != 'CODE_CHANGE':
        print('Fixture workflow requires CODE_CHANGE')
        return 2
    directory = PROJECT/'build'/('workflow-session-'+uuid.uuid4().hex[:12])
    summary = {'status': 'FAILED', 'session': str(directory), 'mysqlContainerStopped': False}
    try:
        with mysql(summary) as (container, version, env):
            analyzer = REPO/'build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar'
            metadata(directory, version, analyzer)
            session = Session(directory, env)
            session.run('baseline')
            for stage in ('test-writing', 'implementation'):
                session.run(stage, '--changes', directory/(stage+'.json'))
            session.run('verification')
            summary['failureChecks'] = session.failures(container)
            session.run('verification')
            session.run('local-service', '--container', container)
            session.stopped()
            snapshot = workflow.read_json(directory/'after.json')
            build_reviews(snapshot, (directory/'scan.json').relative_to(PROJECT).as_posix(), directory)
            session.run('review', '--bundle', directory/'evidence-reviews.json', '--open-evidence', directory/'open.json',
                        '--fixture-policy', PROJECT/'fixture-review-policy.json')
            session.run('complete')
            result = workflow.read_json(directory/'state.json')['completion']
            summary.update({'status': result['status'], 'targetRiskStatus': result['targetRiskStatus'], 'processStopped': True})
    except (ValueError, OSError, subprocess.SubprocessError, KeyboardInterrupt):
        summary['status'] = 'FAILED'
        summary['reason'] = 'Fixture CLI session failed or interrupted; inspect stage records'
    workflow.save(PROJECT/'build/workflow-session-summary.json', summary)
    print(json.dumps(summary), flush=True)
    return 0 if summary['status'] == 'COMPLETED' and summary['mysqlContainerStopped'] else 1


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    raise SystemExit(main())
