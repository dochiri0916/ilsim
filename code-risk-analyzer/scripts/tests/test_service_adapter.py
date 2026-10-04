import importlib.util
from pathlib import Path
import socket
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('service_adapter', Path(__file__).resolve().parents[1] / 'service_adapter.py')
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)

SERVER = '''import http.server, sys
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == '/ready' else 404)
        self.end_headers()
        self.wfile.write(b'ok')
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])), Handler).serve_forever()
'''
CHECK = '''import os, sys, urllib.request
with urllib.request.urlopen(os.environ['SERVICE_BASE_URL'] + '/ready', timeout=5) as response:
    open(os.environ['SCENARIO_REPORT'], 'w').write('{"assertions": 1, "passed": 1}' if response.status == 200 else '{"assertions": 1, "passed": 0}')
    sys.exit(0 if response.status == 200 else 1)
'''


def python_launcher(project):
    return lambda jar, port, arguments: [sys.executable, str(project/'server.py'), str(port)]


class ServiceAdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name).resolve()
        (self.project/'build/libs').mkdir(parents=True)
        (self.project/'build/libs/app.jar').write_text('jar')
        (self.project/'server.py').write_text(SERVER)
        (self.project/'checks').mkdir()
        (self.project/'checks/ok.py').write_text(CHECK)
        (self.project/'checks/fail.py').write_text('import sys; sys.exit(3)')
        self.settings = {'jar': 'build/libs/app.jar', 'readyPath': '/ready', 'readyTimeoutSeconds': 20,
                         'scenarios': {name: ['python3', 'checks/ok.py', name] for name in adapter.SCENARIOS}}

    def test_runs_every_scenario_against_a_started_process_and_stops_it(self):
        settings = adapter.settings(self.settings)
        result = adapter.verify(self.project, settings, {'src/A.java': 'x'}, launcher=python_launcher(self.project))
        self.assertEqual('PASSED', result['status'])
        self.assertEqual(dict.fromkeys(adapter.SCENARIOS, 'PASSED'), result['scenarios'])
        self.assertTrue(result['processStopped'])
        self.assertEqual({'src/A.java': 'x'}, result['sources'])
        self.assertEqual(adapter.digest(self.project/'build/libs/app.jar'), result['jarSha256'])
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', result['port']))

    def test_failed_scenario_fails_the_run_but_still_stops_the_process(self):
        self.settings['scenarios']['rollback'] = ['python3', 'checks/fail.py']
        result = adapter.verify(self.project, adapter.settings(self.settings), {}, launcher=python_launcher(self.project))
        self.assertEqual('FAILED', result['status'])
        self.assertEqual('FAILED', result['scenarios']['rollback'])
        self.assertEqual('PASSED', result['scenarios']['normal'])
        self.assertTrue(result['processStopped'])

    def test_process_that_never_becomes_ready_is_stopped_and_fails(self):
        self.settings['readyPath'] = '/missing'
        self.settings['readyTimeoutSeconds'] = 2
        result = adapter.verify(self.project, adapter.settings(self.settings), {}, launcher=python_launcher(self.project))
        self.assertEqual('FAILED', result['status'])
        self.assertEqual({}, result['scenarios'])
        self.assertTrue(result['processStopped'])

    def test_settings_reject_ambiguous_or_unsafe_configuration(self):
        for change in ({'jar': '../app.jar'}, {'jar': 'build/libs/app.zip'}, {'readyPath': 'ready'},
                       {'scenarios': {'normal': ['python3']}}, {'scenarios': dict.fromkeys(adapter.SCENARIOS, [])},
                       {'arguments': ['--server.port=1']}, {'arguments': ['--server.address=0.0.0.0']},
                       {'readyTimeoutSeconds': 0}, {'unknown': True}):
            with self.assertRaises(ValueError):
                adapter.settings(dict(self.settings, **change))

    def test_referenced_scenario_files_are_hashed_for_drift_detection(self):
        files = adapter.scenario_files(self.project, adapter.settings(self.settings))
        self.assertEqual({'checks/ok.py': adapter.digest(self.project/'checks/ok.py')}, files)

    def test_scenario_program_itself_is_hashed(self):
        (self.project/'checks/run.sh').write_text('exit 0')
        self.settings['scenarios'] = {name: ['checks/run.sh', name] for name in adapter.SCENARIOS}
        files = adapter.scenario_files(self.project, adapter.settings(self.settings))
        self.assertEqual({'checks/run.sh': adapter.digest(self.project/'checks/run.sh')}, files)

    def test_exit_code_zero_without_passed_assertions_is_not_a_pass(self):
        # given: one check asserts nothing, one reports no assertions, one reports a failed assertion
        (self.project/'checks/silent.py').write_text('import sys; sys.exit(0)')
        (self.project/'checks/empty.py').write_text("import os; open(os.environ['SCENARIO_REPORT'], 'w').write('{\"assertions\": 0, \"passed\": 0}')")
        (self.project/'checks/partial.py').write_text("import os; open(os.environ['SCENARIO_REPORT'], 'w').write('{\"assertions\": 2, \"passed\": 1}')")
        self.settings['scenarios'].update(duplicate=['python3', 'checks/silent.py'], concurrency=['python3', 'checks/empty.py'],
                                          rollback=['python3', 'checks/partial.py'])
        # when
        result = adapter.verify(self.project, adapter.settings(self.settings), {}, launcher=python_launcher(self.project))
        # then
        self.assertEqual('FAILED', result['status'])
        self.assertEqual({'normal': 'PASSED', 'duplicate': 'FAILED', 'concurrency': 'FAILED', 'rollback': 'FAILED'},
                         result['scenarios'])
        observed = {item['scenario']: item for item in result['observations']}
        self.assertEqual((0, False), (observed['duplicate']['exitCode'], observed['duplicate']['assertions']['valid']))
        self.assertEqual({'valid': True, 'assertions': 1, 'passed': 1}, observed['normal']['assertions'])

    def test_scenarios_without_project_check_or_identical_commands_are_rejected(self):
        self.settings['scenarios'] = {name: ['true', 'noop-' + name] for name in adapter.SCENARIOS}
        with self.assertRaisesRegex(ValueError, 'project'):
            adapter.scenario_files(self.project, adapter.settings(self.settings))
        self.settings['scenarios'] = {name: ['python3', 'checks/ok.py'] for name in adapter.SCENARIOS}
        with self.assertRaisesRegex(ValueError, 'distinct'):
            adapter.settings(self.settings)


if __name__ == '__main__':
    unittest.main()
