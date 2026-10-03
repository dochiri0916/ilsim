"""General local service verification: start the verified JAR on loopback and run declared scenario commands."""
import hashlib
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

SCENARIOS = ('normal', 'duplicate', 'concurrency', 'rollback')
DEFAULTS = {'arguments': [], 'readyPath': '/actuator/health', 'readyTimeoutSeconds': 60, 'scenarioTimeoutSeconds': 120}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def settings(value):
    if not isinstance(value, dict) or not {'jar', 'scenarios'} <= set(value) or not set(value) <= {'jar', 'scenarios', *DEFAULTS}:
        raise ValueError('Service adapter requires jar and scenarios')
    result = dict(DEFAULTS, **value)
    jar = result['jar']
    if not isinstance(jar, str) or not re.fullmatch(r'build/libs/[A-Za-z0-9._-]+\.jar', jar):
        raise ValueError('Service JAR must be a build/libs file')
    arguments = result['arguments']
    if not isinstance(arguments, list) or any(not isinstance(a, str) or not a for a in arguments):
        raise ValueError('Service arguments must be strings')
    # The adapter owns the bind address and port so the process stays on an exclusive loopback port.
    if any(re.match(r'--server\.(port|address)\b', a) for a in arguments):
        raise ValueError('Service port and address are controlled by the adapter')
    if not isinstance(result['readyPath'], str) or not re.fullmatch(r'/[A-Za-z0-9._~/?=&%-]*', result['readyPath']):
        raise ValueError('Readiness path must be an absolute URL path')
    for name, maximum in (('readyTimeoutSeconds', 600), ('scenarioTimeoutSeconds', 1800)):
        if type(result[name]) is not int or not 1 <= result[name] <= maximum:
            raise ValueError('Invalid service timeout')
    scenarios = result['scenarios']
    if not isinstance(scenarios, dict) or set(scenarios) != set(SCENARIOS):
        raise ValueError('Declare normal, duplicate, concurrency and rollback scenarios')
    for command in scenarios.values():
        if not isinstance(command, list) or len(command) < 2 or any(not isinstance(a, str) or not a for a in command):
            raise ValueError('Each scenario must be a program and its arguments')
    if len({tuple(command) for command in scenarios.values()}) != len(SCENARIOS):
        raise ValueError('Each scenario needs a distinct check command')
    return result


def scenario_files(project, config):
    # The program and its script arguments are hashed so a check cannot change after the session starts.
    files = {}
    for command in config['scenarios'].values():
        referenced = False
        for argument in command:
            path = Path(argument)
            if not path.is_absolute() and '..' not in path.parts and (project/path).is_file():
                files[path.as_posix()] = digest(project/path)
                referenced = True
        if not referenced:
            raise ValueError('Each scenario must run a check script inside the project')
    return files


def java_launcher(jar, port, arguments):
    return ['java', '-jar', str(jar), '--server.address=127.0.0.1', '--server.port=' + str(port), *arguments]


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def ready(url, process, deadline):
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return False
        try:
            with urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return True
        except (HTTPError, URLError, OSError):
            pass
        time.sleep(0.2)
    return False


def stop(process):
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
    return process.poll() is not None


def port_released(port):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        try:
            with socket.socket() as probe:
                # TIME_WAIT connections are not a running listener; only an active bind should fail.
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(('127.0.0.1', port))
                return True
        except OSError:
            time.sleep(0.2)
    return False


def verify(project, config, sources, launcher=java_launcher):
    jar = project/config['jar']
    port = free_port()
    base_url = 'http://127.0.0.1:' + str(port)
    result = {'status': 'FAILED', 'adapter': 'GENERAL', 'sources': sources, 'jarSha256': digest(jar), 'port': port,
              'scenarios': {}, 'observations': [], 'processStopped': False,
              'limitations': ['Scenario commands own their assertions; exit code 0 is recorded as PASSED',
                              'Loopback process only; external systems are whatever the declared environment provides']}
    process = None
    try:
        # Process output can carry secrets, so it is discarded rather than stored as evidence.
        with tempfile.TemporaryFile() as output:
            process = subprocess.Popen(launcher(jar, port, config['arguments']), cwd=project, stdout=output,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            if not ready(base_url + config['readyPath'], process, time.monotonic() + config['readyTimeoutSeconds']):
                result['reason'] = 'Service did not become ready'
                return result
            environment = dict(os.environ, SERVICE_BASE_URL=base_url)
            for name in SCENARIOS:
                try:
                    completed = subprocess.run(config['scenarios'][name], cwd=project, env=environment,
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                               timeout=config['scenarioTimeoutSeconds'])
                    code = completed.returncode
                except subprocess.TimeoutExpired:
                    code = 'TIMEOUT'
                result['scenarios'][name] = 'PASSED' if code == 0 else 'FAILED'
                result['observations'].append({'scenario': name, 'exitCode': code})
                if process.poll() is not None:
                    result['reason'] = 'Service exited during scenarios'
                    return result
            if all(state == 'PASSED' for state in result['scenarios'].values()):
                result['status'] = 'PASSED'
            else:
                result['reason'] = 'Service scenario failed'
    except OSError:
        result['reason'] = 'Service process could not be started'
    finally:
        if process is not None:
            result['processStopped'] = stop(process) and port_released(port)
        if not result['processStopped']:
            result['status'] = 'FAILED'
    return result
