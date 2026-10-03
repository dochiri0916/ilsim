import json
import sys
import unittest
from unittest.mock import patch

import test_code_change_workflow as base

controller = base.controller
CHECK = '''import os, sys, urllib.request
with urllib.request.urlopen(os.environ['SERVICE_BASE_URL'] + '/ready', timeout=5) as response:
    sys.exit(0 if response.status == 200 else 1)
'''
SERVER = '''import http.server, sys
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])), Handler).serve_forever()
'''


class GeneralServiceStageTests(unittest.TestCase):
    locals().update({name: getattr(base.CodeChangeWorkflowTests, name) for name in
                     ('put', 'run_stage', 'writer', 'changes', 'implementation', 'verify')})

    def setUp(self):
        base.CodeChangeWorkflowTests.setUp(self)
        self.put('checks/scenario.py', CHECK)
        self.put('server.py', SERVER)
        self.config['serviceAdapter'] = {'jar': 'build/libs/service.jar', 'readyPath': '/ready', 'readyTimeoutSeconds': 20,
                                         'scenarios': {name: ['python3', 'checks/scenario.py', name] for name in
                                                       ('normal', 'duplicate', 'concurrency', 'rollback')}}
        launcher = lambda jar, port, arguments: [sys.executable, str(self.project/'server.py'), str(port)]
        patcher = patch.object(controller.service_adapter, 'java_launcher', launcher)
        patcher.start()
        self.addCleanup(patcher.stop)

    def review(self, service=None):
        snap = controller.regression.read_json(self.project/'build/session/after.json')
        report = self.project/'build/session/scan.json'
        bundle = {'version': 1, 'snapshotSha256': controller.evidence.fingerprint(snap),
                  'reportSha256': controller.regression.digest(report), 'records': []}
        return self.run_stage('review', bundle=bundle, service=service, open_evidence=[])

    def test_general_adapter_runs_service_and_completes_without_external_evidence(self):
        self.implementation()
        self.assertEqual('PASSED', self.verify()['status'])
        result = self.run_stage('local-service')
        self.assertEqual('PASSED', result['status'])
        self.assertTrue(result['processStopped'])
        self.assertEqual('COMPLETED', self.review()['status'])
        self.assertEqual('COMPLETED', self.run_stage('complete')['status'])

    def test_review_requires_controller_service_run_and_rejects_substitutes(self):
        self.implementation()
        self.verify()
        with self.assertRaisesRegex(ValueError, 'local-service'):
            self.review()
        self.run_stage('local-service')
        with self.assertRaisesRegex(ValueError, 'External service'):
            self.review(service={'status': 'PASSED'})

    def test_failed_scenario_blocks_review(self):
        self.put('checks/fail.py', 'import sys; sys.exit(1)')
        self.config['serviceAdapter']['scenarios']['rollback'] = ['python3', 'checks/fail.py']
        self.implementation()
        self.verify()
        self.assertEqual('FAILED', self.run_stage('local-service')['status'])
        with self.assertRaisesRegex(ValueError, 'local-service'):
            self.review()

    def test_scenario_change_invalidates_session(self):
        self.implementation()
        self.verify()
        self.put('checks/scenario.py', 'import sys; sys.exit(0)')
        with self.assertRaisesRegex(ValueError, 'Changed inputs'):
            self.run_stage('local-service')

    def test_adapter_choices_are_exclusive_and_container_is_rejected(self):
        with self.assertRaises(ValueError):
            controller.Controller(dict(self.config, fixtureAdapter='MYSQL'), self.state)
        self.implementation()
        self.verify()
        with self.assertRaisesRegex(ValueError, 'container'):
            self.run_stage('local-service', container='code-risk-sample-000000000000')


if __name__ == '__main__':
    unittest.main()
