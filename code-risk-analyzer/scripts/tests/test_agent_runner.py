import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('agent_runner', Path(__file__).resolve().parents[1]/'agent_runner.py')
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


class AgentRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.workspace = self.root/'workspace'
        self.workspace.mkdir()
        self.bin = self.root/'bin'
        self.bin.mkdir()
        self.environ = patch.dict(os.environ, {'PATH': str(self.bin) + os.pathsep + os.environ['PATH']})
        self.environ.start()
        self.addCleanup(self.environ.stop)
        self.payload = {'status': 'COMPLETED', 'changes': [{'path': 'src/main/Service.java', 'content': 'class Service {}'}],
                        'requirementTestMap': [], 'expectedFailures': [], 'unknowns': []}

    def binary(self, provider, body):
        path = self.bin/provider
        path.write_text('#!' + sys.executable + '\nimport json, os, sys\nfrom pathlib import Path\n' + body)
        path.chmod(0o700)

    def provider(self, provider, payload=None, trailer=None):
        payload = self.payload if payload is None else payload
        record = 'Path("invocation.json").write_text(json.dumps({"args":sys.argv[1:],"cwd":os.getcwd(),"prompt":sys.stdin.read()}))\n'
        if provider == 'codex':
            record += 'Path(sys.argv[sys.argv.index("--output-last-message")+1]).write_text(' + repr(json.dumps(payload)) + ')\n'
            record += 'print(json.dumps({"type":"thread.started","thread_id":"fresh"}))\n'
            record += 'print(json.dumps(' + repr(trailer or {'type': 'turn.completed'}) + '))\n'
        else:
            record += 'print(json.dumps(' + repr(trailer or {'type': 'result', 'subtype': 'success', 'is_error': False, 'structured_output': payload}) + '))\n'
        self.binary(provider, record)

    def test_both_providers_use_explicit_workspace_stdin_and_fresh_sessions(self):
        for provider in ('codex', 'claude'):
            with self.subTest(provider=provider):
                self.provider(provider)
                config = {'provider': provider, 'model': 'approved-model', 'allowedModels': ['approved-model']}
                first = agent.run(config, self.workspace, 'sensitive prompt')
                second = agent.run(config, self.workspace, 'independent prompt')
                self.assertEqual('COMPLETED', first['status'])
                self.assertEqual(self.payload, first['output'])
                self.assertNotEqual(first['attemptId'], second['attemptId'])
                recorded = json.loads((self.workspace/'invocation.json').read_text())
                self.assertEqual(str(self.workspace), recorded['cwd'])
                self.assertEqual('independent prompt', recorded['prompt'])
                self.assertNotIn('independent prompt', recorded['args'])
                self.assertIn('approved-model', recorded['args'])
                self.assertNotIn('--resume', recorded['args'])
                self.assertNotIn('--continue', recorded['args'])
                if provider == 'codex':
                    self.assertIn('read-only', recorded['args'])
                    self.assertIn('--ephemeral', recorded['args'])
                else:
                    self.assertIn('--no-session-persistence', recorded['args'])
                    self.assertEqual('', recorded['args'][recorded['args'].index('--tools') + 1])

    def test_config_rejects_flag_injection_unknown_options_and_unapproved_models(self):
        for config in ({'provider': 'unknown'}, {'provider': 'codex', 'model': '--unsafe', 'allowedModels': ['--unsafe']},
                       {'provider': 'codex', 'model': 'unapproved'}, {'provider': 'codex', 'timeoutSeconds': True},
                       {'provider': 'codex', 'timeoutSeconds': 0}, {'provider': 'claude', 'credential': 'secret'}):
            with self.subTest(config=config), self.assertRaises(ValueError):
                agent.run(config, self.workspace, 'prompt')
        self.assertFalse((self.workspace/'invocation.json').exists())

    def test_explicit_reasoning_is_translated_for_each_provider_and_recorded(self):
        for provider in ('codex', 'claude'):
            with self.subTest(provider=provider):
                # given
                self.provider(provider)
                config = {'provider': provider, 'model': 'approved-model', 'allowedModels': ['approved-model'],
                          'reasoningEffort': 'high'}
                # when
                result = agent.run(config, self.workspace, 'prompt')
                # then
                self.assertEqual('COMPLETED', result['status'])
                self.assertEqual('high', result['reasoningEffort'])
                args = json.loads((self.workspace/'invocation.json').read_text())['args']
                if provider == 'codex':
                    self.assertIn('model_reasoning_effort="high"', args)
                else:
                    self.assertEqual('high', args[args.index('--effort') + 1])

    def test_invalid_effort_or_effort_without_explicit_model_never_launches_cli(self):
        for config in ({'provider': 'codex', 'reasoningEffort': 'high'},
                       {'provider': 'claude', 'model': 'approved-model', 'allowedModels': ['approved-model'], 'reasoningEffort': '--unsafe'},
                       {'provider': 'codex', 'model': 'approved-model', 'allowedModels': ['approved-model'], 'reasoningEffort': True},
                       {'provider': 'codex', 'model': 'approved-model', 'allowedModels': ['approved-model'], 'reasoningEffort': None}):
            with self.subTest(config=config), patch.object(agent.subprocess, 'Popen') as launch:
                # when / then
                with self.assertRaises(ValueError):
                    agent.run(config, self.workspace, 'prompt')
                launch.assert_not_called()

    def test_omitted_effort_preserves_cli_defaults_and_reports_no_override(self):
        for provider in ('codex', 'claude'):
            with self.subTest(provider=provider):
                # given
                self.provider(provider)
                # when
                result = agent.run({'provider': provider}, self.workspace, 'prompt')
                # then
                self.assertEqual('COMPLETED', result['status'])
                self.assertIsNone(result['reasoningEffort'])
                args = json.loads((self.workspace/'invocation.json').read_text())['args']
                self.assertNotIn('--effort', args)
                self.assertFalse(any('model_reasoning_effort' in arg for arg in args))

    def test_missing_binary_and_cli_failure_do_not_expose_output(self):
        with patch.object(agent.shutil, 'which', return_value=None):
            self.assertEqual('UNAVAILABLE', agent.run({'provider': 'codex'}, self.workspace, 'prompt')['status'])
        self.binary('codex', 'print("credential must not be returned")\nprint("secret stderr", file=sys.stderr)\nsys.exit(4)\n')
        result = agent.run({'provider': 'codex'}, self.workspace, 'prompt')
        self.assertEqual('FAILED', result['status'])
        self.assertEqual(4, result['exitCode'])
        self.assertNotIn('secret', json.dumps(result))
        self.assertNotIn('credential', json.dumps(result))

    def test_errors_and_missing_terminal_events_cannot_become_success(self):
        for provider, trailer in (('codex', {'type': 'turn.failed'}), ('codex', {'type': 'item.completed'}),
                                  ('claude', {'type': 'result', 'subtype': 'success', 'is_error': True, 'structured_output': self.payload}),
                                  ('claude', {'type': 'result', 'subtype': 'error_max_turns', 'is_error': False, 'structured_output': self.payload})):
            with self.subTest(provider=provider, trailer=trailer):
                self.provider(provider, trailer=trailer)
                self.assertEqual('INVALID_OUTPUT', agent.run({'provider': provider}, self.workspace, 'prompt')['status'])

    def test_insufficient_balance_is_unavailable_without_echoing_provider_error(self):
        self.binary('claude', 'sys.stdin.read()\nprint(json.dumps({"type":"result","is_error":True,"result":"API Error: 402 Insufficient Balance secret"}))\nsys.exit(1)\n')
        result = agent.run({'provider': 'claude'}, self.workspace, 'prompt')
        self.assertEqual('UNAVAILABLE', result['status'])
        self.assertEqual('Provider account balance is insufficient', result['reason'])
        self.assertNotIn('secret', json.dumps(result))

    def test_malformed_duplicate_and_wrong_shape_json_is_rejected(self):
        for output in ('invalid', '{"type":"result","type":"result"}', '[]', '{}'):
            with self.subTest(output=output):
                self.binary('claude', 'sys.stdin.read()\nprint(' + repr(output) + ')\n')
                self.assertEqual('INVALID_OUTPUT', agent.run({'provider': 'claude'}, self.workspace, 'prompt')['status'])
        payload = dict(self.payload, changes=[{'path': '../escape.java', 'content': 'bad'}])
        self.provider('codex', payload)
        self.assertEqual('INVALID_OUTPUT', agent.run({'provider': 'codex'}, self.workspace, 'prompt')['status'])

    def test_review_request_is_preserved_without_changes(self):
        payload = dict(self.payload, status='NEEDS_REVIEW', changes=[], unknowns=['Specify expected behavior'])
        self.provider('claude', payload)
        result = agent.run({'provider': 'claude'}, self.workspace, 'prompt')
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertEqual([], result['output']['changes'])
        self.provider('claude', dict(payload, changes=self.payload['changes']))
        self.assertEqual('INVALID_OUTPUT', agent.run({'provider': 'claude'}, self.workspace, 'prompt')['status'])

    def test_only_completed_provider_turn_can_report_retryable_output_contract_failure(self):
        for provider in ('codex', 'claude'):
            with self.subTest(provider=provider):
                # given
                self.provider(provider, dict(self.payload, unknowns=['unresolved']))
                # when
                result = agent.run({'provider': provider}, self.workspace, 'prompt')
                # then
                self.assertEqual('OUTPUT_CONTRACT', result['failureKind'])
                trailer = {'type': 'turn.failed'} if provider == 'codex' else {'type': 'result', 'is_error': True}
                self.provider(provider, trailer=trailer)
                result = agent.run({'provider': provider}, self.workspace, 'prompt')
                self.assertEqual('PROVIDER_FAILURE', result['failureKind'])
                self.assertNotIn('output', result)

    def test_jev_credential_is_not_inherited_by_generator_process(self):
        # given
        self.binary('codex', 'sys.stdin.read()\nPath("key-present").write_text(str("TYPESAFE_API_KEY" in os.environ))\nsys.exit(1)\n')
        with patch.dict(os.environ, {'TYPESAFE_API_KEY': 'private-test-key'}):
            # when
            result = agent.run({'provider': 'codex'}, self.workspace, 'prompt')
        # then
        self.assertEqual('False', (self.workspace/'key-present').read_text())
        self.assertNotIn('private-test-key', json.dumps(result))

    def test_unsafe_output_paths_and_ambiguous_contracts_are_not_retryable(self):
        for payload, kind in ((dict(self.payload, changes=[{'path': '../escape.java', 'content': 'bad'}]), 'SCOPE'),
                              (dict(self.payload, status='NEEDS_REVIEW', unknowns=['unclear']), 'AMBIGUITY')):
            with self.subTest(kind=kind):
                # given
                self.provider('codex', payload)
                # when
                result = agent.run({'provider': 'codex'}, self.workspace, 'prompt')
                # then
                self.assertEqual(kind, result['failureKind'])
                self.assertEqual('INVALID_OUTPUT', result['status'])
                self.assertNotIn('output', result)

    def test_literal_display_names_are_supported_and_blank_or_multiline_names_rejected(self):
        payload = dict(self.payload, requirementTestMap=[{'requirementId': 'REQ-1', 'scenarioId': 'SCN-ZERO',
                      'tests': ['example.BoundaryTest#하한 경계인 0을 허용한다']}],
                      expectedFailures=[{'test': 'example.BoundaryTest#하한 경계인 0을 허용한다',
                                         'kind': 'ASSERTION', 'reason': 'Zero is rejected'}])
        self.assertEqual(payload, agent.validate_output(payload))
        for name in ('example.BoundaryTest# ', 'example.BoundaryTest#bad\nname'):
            invalid = json.loads(json.dumps(payload))
            invalid['expectedFailures'][0]['test'] = name
            with self.subTest(name=name), self.assertRaises(ValueError): agent.validate_output(invalid)

    def test_timeout_cleans_child_even_when_child_ignores_term(self):
        port_file = self.root/'child.port'
        child = 'import signal,socket,time; from pathlib import Path; signal.signal(signal.SIGTERM, signal.SIG_IGN); ' \
                's=socket.socket(); s.bind(("127.0.0.1",0)); s.listen(); ' \
                'Path(' + repr(str(port_file)) + ').write_text(str(s.getsockname()[1])); time.sleep(60)'
        self.binary('codex', 'import subprocess, time\n'
            'child = subprocess.Popen([sys.executable, "-c", ' + repr(child) + '])\ntime.sleep(60)\n')
        result = agent.run({'provider': 'codex', 'timeoutSeconds': 1}, self.workspace, 'prompt')
        self.assertEqual('TIMED_OUT', result['status'])
        with socket.socket() as released:
            released.bind(('127.0.0.1', int(port_file.read_text())))
            self.assertEqual(int(port_file.read_text()), released.getsockname()[1])

    def test_interrupt_cleans_process_before_propagating(self):
        self.binary('claude', 'import time\ntime.sleep(60)\n')
        original = agent.subprocess.Popen
        processes = []
        def launch(*args, **kwargs):
            process = original(*args, **kwargs)
            processes.append(process)
            return process
        with patch.object(agent.subprocess, 'Popen', side_effect=launch), \
                patch.object(original, 'communicate', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                agent.run({'provider': 'claude'}, self.workspace, 'prompt')
        self.assertIsNotNone(processes[0].poll())


if __name__ == '__main__':
    unittest.main()
