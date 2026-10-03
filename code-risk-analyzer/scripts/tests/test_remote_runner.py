import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


remote = load('remote_runner')
ask = load('ask')


class RunnerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.project = Path(temp.name).resolve()/'demo'
        (self.project/'build/ask').mkdir(parents=True)
        self.posted, self.queue, self.commands = [], [], []

    def session(self, name, phase=None):
        session = self.project/'build/ask'/name
        session.mkdir()
        (session/ask.SESSION_CONFIG).write_text('{}')
        if phase:
            (session/'state.json').write_text(json.dumps({'phase': phase}))
        (self.project/'build/ask/current').write_text(name)

    def runner(self, *outputs):
        outputs = list(outputs)

        def post(status):
            self.posted.append(json.loads(json.dumps(status)))
            commands, self.queue[:] = list(self.queue), []
            return commands

        def run(command, **options):
            self.commands.append(command)
            code, output, session = outputs.pop(0)
            if session:
                self.session(session)
            self.assertNotIn('ASK_RUNNER_TOKEN', options['env'])
            return subprocess.CompletedProcess(command, code, output)
        return remote.Runner(self.project, post, run)

    def test_request_drafts_then_matching_approval_runs_yes(self):
        # given
        runner = self.runner((0, '초안: 회원 탈퇴\n' + ask.NOT_CONFIRMED, '20261003-120000'), (0, '완료', None))
        self.queue.append({'type': 'request', 'text': '회원 탈퇴 추가해줘'})
        # when
        runner.step()
        # then
        self.assertEqual(['회원 탈퇴 추가해줘'], self.commands[0][-1:])
        self.assertEqual({'name': '20261003-120000', 'phase': None, 'awaitingApproval': True}, self.posted[-1]['session'])
        self.assertIn('초안: 회원 탈퇴', self.posted[-1]['last']['summary'])
        # when
        self.queue.append({'type': 'approve', 'session': '20261003-120000'})
        runner.step()
        # then
        self.assertEqual(['--yes'], self.commands[1][-1:])
        self.assertFalse(self.posted[-1]['session']['awaitingApproval'])
        self.assertEqual(0, self.posted[-1]['last']['exitCode'])

    def test_approval_for_another_session_is_refused(self):
        # given
        runner = self.runner((0, ask.NOT_CONFIRMED, 's2'))
        self.queue.append({'type': 'request', 'text': '추가해줘'})
        runner.step()
        # when
        self.queue.append({'type': 'approve', 'session': 's1'})
        runner.step()
        # then
        self.assertEqual(1, len(self.commands))
        self.assertIn('승인하지 않았습니다', self.posted[-1]['last']['summary'])

    def test_approval_without_a_waiting_plan_is_refused(self):
        # given: a session drafted on the PC, not through the phone
        self.session('s1', 'TEST_WRITING')
        runner = self.runner()
        # when
        self.queue.append({'type': 'approve', 'session': 's1'})
        runner.step()
        # then
        self.assertEqual([], self.commands)
        self.assertEqual('TEST_WRITING', self.posted[-1]['session']['phase'])
        self.assertIn('승인하지 않았습니다', self.posted[-1]['last']['summary'])

    def test_failed_or_read_only_request_does_not_wait_for_approval(self):
        runner = self.runner((2, '요청 거부: 이유', None), (0, '상태: TEST_WRITING', 's1'))
        self.queue.extend([{'type': 'request', 'text': 'a'}, {'type': 'request', 'text': '상태 알려줘'}])
        runner.step()
        self.assertFalse(self.posted[-1]['session']['awaitingApproval'])
        self.assertEqual(0, self.posted[-1]['last']['exitCode'])

    def test_busy_status_is_reported_while_ask_runs(self):
        runner = self.runner((0, 'ok', None))
        self.queue.append({'type': 'request', 'text': '분석해줘'})
        runner.step()
        self.assertEqual('요청: 분석해줘', self.posted[1]['busy'])
        self.assertIsNone(self.posted[-1]['busy'])

    def test_text_is_passed_as_one_argument_never_through_a_shell(self):
        runner = self.runner((0, 'ok', None))
        self.queue.append({'type': 'request', 'text': '$(rm -rf ~); echo hi'})
        runner.step()
        self.assertEqual('$(rm -rf ~); echo hi', self.commands[0][-1])
        self.assertIn('--project', self.commands[0])

    def test_unknown_command_is_skipped(self):
        runner = self.runner()
        self.queue.append({'type': 'shell', 'text': 'ls'})
        runner.step()
        self.assertEqual([], self.commands)


class TelegramTests(unittest.TestCase):
    def setUp(self):
        self.updates, self.sent, self.calls = [], [], []

    def call(self, method, **payload):
        self.calls.append((method, payload))
        if method == 'sendMessage':
            self.sent.append((payload['chat_id'], payload['text']))
            return {}
        start = payload.get('offset', 0)
        if start == -1:
            return self.updates[-1:]
        return [update for update in self.updates if update['update_id'] >= start]

    def message(self, text, chat=42):
        self.updates.append({'update_id': len(self.updates) + 1, 'message': {'chat': {'id': chat}, 'text': text}})

    def status(self, awaiting=False, last=None, busy=None):
        return {'project': 'demo', 'busy': busy, 'last': last,
                'session': {'name': 's1', 'phase': None, 'awaitingApproval': awaiting}}

    def test_only_the_owner_chat_becomes_requests_and_old_messages_are_skipped(self):
        # given
        self.message('어제 보낸 요청')
        link = remote.TelegramLink(self.call, '42')
        link.skip_old()
        self.message('회원 탈퇴 추가해줘')
        self.message('남이 보낸 요청', chat=7)
        # when
        commands = link(self.status())
        # then
        self.assertEqual([{'type': 'request', 'text': '회원 탈퇴 추가해줘'}], commands)
        self.assertEqual([], link(self.status()))

    def test_approve_only_when_the_session_waits(self):
        link = remote.TelegramLink(self.call, '42')
        self.message('승인')
        self.assertEqual([], link(self.status(awaiting=False)))
        self.assertIn('승인할 작업이 없습니다', self.sent[-1][1])
        self.message('/approve')
        self.assertEqual([{'type': 'approve', 'session': 's1'}], link(self.status(awaiting=True)))

    def test_status_and_results_are_sent_to_the_chat(self):
        link = remote.TelegramLink(self.call, '42')
        self.message('상태')
        link(self.status(awaiting=True))
        self.assertIn('승인을 기다립니다', self.sent[-1][1])
        link(self.status(busy='요청: x'))
        self.assertEqual(('42', '시작했습니다: 요청: x'), self.sent[-1])
        link(self.status(last={'command': '요청: x', 'exitCode': 0, 'summary': 'y' * 9000}))
        self.assertIn('요청: x: 완료', self.sent[-1][1])
        self.assertLessEqual(len(self.sent[-1][1]), remote.TELEGRAM_LIMIT)

    def test_runner_drives_a_telegram_request(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder).resolve()
            link = remote.TelegramLink(self.call, '42', poll=0)
            self.message('추가해줘')
            runs = []

            def run(command, **options):
                runs.append(command)
                self.assertNotIn('TELEGRAM_BOT_TOKEN', options['env'])
                return subprocess.CompletedProcess(command, 0, '초안')
            remote.Runner(project, link, run).step()
        self.assertEqual('추가해줘', runs[0][-1])
        self.assertIn('요청: 추가해줘: 완료', self.sent[-1][1])


class SettingTests(unittest.TestCase):
    def test_telegram_settings_are_optional_and_checked(self):
        with tempfile.TemporaryDirectory() as folder:
            secrets = Path(folder)/'secrets'
            secrets.write_text('TELEGRAM_BOT_TOKEN=123:abc\nTELEGRAM_CHAT_ID=42\n')
            self.assertEqual(('123:abc', '42'), remote.telegram_settings({'CODE_RISK_SECRETS': str(secrets)}))
            self.assertIsNone(remote.telegram_settings({'CODE_RISK_SECRETS': str(Path(folder)/'none')}))
            with self.assertRaises(ValueError):
                remote.telegram_settings({'CODE_RISK_SECRETS': folder, 'TELEGRAM_BOT_TOKEN': 't',
                                          'TELEGRAM_CHAT_ID': '42; rm'})

    def test_settings_come_from_env_or_secrets_file(self):
        with tempfile.TemporaryDirectory() as folder:
            secrets = Path(folder)/'secrets'
            secrets.write_text('export ASK_RUNNER_TOKEN="abc123456789xyz"\nTYPESAFE_API_KEY=k\n')
            value = remote.settings({'CODE_RISK_SECRETS': str(secrets), 'ASK_REMOTE_URL': 'https://x.vercel.app/'})
        self.assertEqual(('https://x.vercel.app', 'abc123456789xyz'), value)

    def test_missing_or_plain_http_settings_are_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                remote.settings({'CODE_RISK_SECRETS': str(Path(folder)/'none')})
            with self.assertRaises(ValueError):
                remote.settings({'CODE_RISK_SECRETS': folder, 'ASK_REMOTE_URL': 'http://x', 'ASK_RUNNER_TOKEN': 't'})

    def test_summary_keeps_the_end(self):
        text = '\n'.join(f'line {number}' for number in range(2000))
        value = remote.summary(text, 100)
        self.assertTrue(value.startswith('...\n'))
        self.assertTrue(value.endswith('line 1999'))
        self.assertLessEqual(len(value), 104)


if __name__ == '__main__':
    unittest.main()
