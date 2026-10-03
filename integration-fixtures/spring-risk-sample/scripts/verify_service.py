"""Packaged fixture process verification; used only with the dedicated MySQL container."""
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

PROJECT = Path(__file__).resolve().parents[1]
REPO = PROJECT.parents[1]
spec = importlib.util.spec_from_file_location('regression_workflow', REPO / 'scripts/regression_workflow.py')
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


def verify_service(env, container, progress_path=None):
    sources = workflow.source_hashes(PROJECT)
    jar = PROJECT / 'build/libs/spring-risk-sample.jar'
    result = {'status': 'FAILED', 'sources': sources, 'jarSha256': workflow.digest(jar),
              'scenarios': {}, 'observations': [], 'processStopped': False,
              'command': ['java', '-jar', 'build/libs/spring-risk-sample.jar', '--sample.service.enabled=true'],
              'limitations': ['Loopback fixture only; one MySQL instance, no broker or external effects']}
    process = None
    try:
        with tempfile.TemporaryFile(mode='w+') as output:
            process = subprocess.Popen(['java', '-jar', str(jar), '--sample.service.enabled=true'],
                                       cwd=PROJECT, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            result['processId'] = process.pid
            if progress_path is not None:
                workflow.save(progress_path, {'processId': process.pid})
            deadline = time.monotonic() + 45
            port = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Service exited before readiness')
                output.seek(0)
                match = re.search(r'RISK_SAMPLE_READY_PORT=(\d+)', output.read())
                if match:
                    port = int(match.group(1))
                    break
                time.sleep(0.1)
            if port is None:
                raise RuntimeError('Service readiness timed out')

            def request(method, route):
                try:
                    with urlopen(Request(f'http://127.0.0.1:{port}{route}', method=method), timeout=10) as response:
                        return response.status, response.read().decode()
                except HTTPError as error:
                    return error.code, error.read().decode()

            def check(method, route, expected):
                actual = request(method, route)
                result['observations'].append({'method': method, 'route': route, 'status': actual[0], 'body': actual[1]})
                if actual != expected:
                    raise RuntimeError('HTTP result differed from expected contract')

            def db_count(key, expected):
                # Keys are fixed constants here, never request input or shell fragments.
                if not re.fullmatch(r'[a-z-]+', key):
                    raise RuntimeError('Invalid verification key')
                query = f"SELECT COUNT(*) FROM safe_record WHERE business_key='{key}'"
                completed = subprocess.run(['docker', 'exec', container, 'sh', '-c',
                    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -N -e "$1"',
                    'mysql-check', query], capture_output=True, text=True, timeout=15)
                if completed.returncode or completed.stdout.strip() != str(expected):
                    raise RuntimeError('Independent committed DB state differed')
                result['observations'].append({'databaseQuery': query, 'count': expected})

            check('GET', '/ready', (200, 'ready'))
            check('GET', '/discount?quantity=9', (200, '0'))
            check('GET', '/discount?quantity=10', (200, '10'))
            check('GET', '/discount?quantity=0', (400, 'invalid input'))
            check('POST', '/records?key=service-normal', (201, 'created'))
            check('GET', '/records?key=service-normal', (200, '1'))
            db_count('service-normal', 1)
            result['scenarios']['normal'] = 'PASSED'
            check('POST', '/records?key=service-normal', (409, 'conflict'))
            db_count('service-normal', 1)
            result['scenarios']['duplicate'] = 'PASSED'
            barrier = threading.Barrier(2)
            def create():
                barrier.wait(timeout=5)
                return request('POST', '/records?key=service-concurrent')
            with ThreadPoolExecutor(max_workers=2) as workers:
                futures = [workers.submit(create) for _ in range(2)]
                responses = [future.result(timeout=15) for future in futures]
            result['observations'].append({'parallelResponses': responses})
            if sorted(responses) != [(201, 'created'), (409, 'conflict')]:
                raise RuntimeError('Concurrent commit/conflict contract differed')
            db_count('service-concurrent', 1)
            result['scenarios']['concurrency'] = 'PASSED'
            check('POST', '/fail?key=service-rollback', (500, 'operation failed'))
            check('GET', '/records?key=service-rollback', (200, '0'))
            db_count('service-rollback', 0)
            check('POST', '/records?key=service-rollback', (201, 'created'))
            db_count('service-rollback', 1)
            result['scenarios']['rollback'] = 'PASSED'
            if workflow.source_hashes(PROJECT) != sources or workflow.digest(jar) != result['jarSha256']:
                raise RuntimeError('Source or artifact changed during service verification')
            result['status'] = 'PASSED'
    except (OSError, RuntimeError, subprocess.SubprocessError, URLError, TimeoutError):
        result['reason'] = 'Service scenario failed; inspect safe observations and local build'
    finally:
        if process is not None:
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            result['processStopped'] = process.poll() is not None
        if not result['processStopped']:
            result['status'] = 'FAILED'
        workflow.save(PROJECT / 'build/service-verification.json', result)
        if progress_path is not None:
            Path(progress_path).unlink(missing_ok=True)
    return result
