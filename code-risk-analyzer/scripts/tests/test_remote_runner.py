from contextlib import redirect_stdout
import io
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


class RunnerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.project = Path(temp.name).resolve()/'demo'
        self.project.mkdir()
        self.posted, self.queue, self.commands = [], [], []

    def runner(self, *outputs):
        outputs = list(outputs)

        def post(status):
            self.posted.append(json.loads(json.dumps(status)))
            commands, self.queue[:] = list(self.queue), []
            return commands

        def run(command, **options):
            self.commands.append((command, options['cwd']))
            self.assertNotIn('ASK_RUNNER_TOKEN', options['env'])
            code, output = outputs.pop(0)
            return subprocess.CompletedProcess(command, code, output)
        return remote.Runner(self.project, post, run)

    def test_a_request_runs_ai_in_the_project_with_the_text_as_one_argument(self):
        # given
        runner = self.runner((0, 'ai: codex (...)\n수정했습니다\n위험 분석: HIGH·CRITICAL 없음'))
        self.queue.append({'type': 'request', 'text': '회원 탈퇴 추가해줘; rm -rf /'})
        # when
        runner.step()
        # then
        command, cwd = self.commands[0]
        self.assertTrue(command[1].endswith('agent_pick.py'))
        self.assertEqual('회원 탈퇴 추가해줘; rm -rf /', command[2])
        self.assertEqual(self.project, cwd)
        self.assertEqual({'command': '요청: 회원 탈퇴 추가해줘; rm -rf /', 'exitCode': 0}, {k: runner.last[k] for k in ('command', 'exitCode')})
        self.assertIn('위험 분석', runner.last['summary'])

    def test_busy_status_is_reported_while_ai_runs(self):
        runner = self.runner((0, 'ok'))
        self.queue.append({'type': 'request', 'text': '추가해줘'})
        runner.step()
        self.assertIn('요청: 추가해줘', [status['busy'] for status in self.posted])

    def test_approval_no_longer_exists_and_unknown_commands_are_skipped(self):
        runner = self.runner()
        self.queue.extend([{'type': 'approve', 'session': 's1'}])
        runner.step()
        self.assertEqual((2, remote.NO_APPROVAL), (runner.last['exitCode'], runner.last['summary']))
        self.queue.append({'type': 'nonsense'})
        runner.step()
        self.assertEqual('알 수 없는 명령', runner.last['command'])
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

    def status(self, last=None, busy=None):
        return {'project': 'demo', 'busy': busy, 'last': last, 'session': None}

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

    def test_approve_is_answered_that_ai_needs_none(self):
        link = remote.TelegramLink(self.call, '42')
        self.message('승인')
        self.assertEqual([], link(self.status()))
        self.assertEqual(remote.NO_APPROVAL, self.sent[-1][1])

    def test_status_and_results_are_sent_to_the_chat(self):
        link = remote.TelegramLink(self.call, '42')
        self.message('상태')
        link(self.status())
        self.assertIn('프로젝트: demo', self.sent[-1][1])
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
                return subprocess.CompletedProcess(command, 0, '수정했습니다')
            remote.Runner(project, link, run).step()
        self.assertEqual('추가해줘', runs[0][-1])
        self.assertIn('요청: 추가해줘: 완료', self.sent[-1][1])


class RestartTests(unittest.TestCase):
    def restart(self, current, awaiting=None, environ=None):
        calls = []
        runner = remote.Runner(Path('/tmp'), lambda status: [])
        runner.awaiting = awaiting
        with redirect_stdout(io.StringIO()):
            restarted = remote.restart_if_changed('old', runner, ['--project', '/p'], environ or {}, lambda: current,
                                                  lambda *args: calls.append(args))
        return restarted, calls

    def test_unchanged_code_keeps_running(self):
        self.assertEqual((False, []), self.restart('old'))

    def test_new_code_restarts_with_the_same_arguments_and_the_awaited_session(self):
        restarted, calls = self.restart('new', awaiting='20261003-101010', environ={'PATH': '/bin'})
        self.assertTrue(restarted)
        executable, arguments, environment = calls[0]
        self.assertEqual(['--project', '/p'], arguments[2:])
        self.assertTrue(arguments[1].endswith('remote_runner.py'))
        self.assertEqual({'PATH': '/bin', remote.RESUME: '20261003-101010'}, environment)

    def test_nothing_awaited_carries_nothing_over(self):
        _, calls = self.restart('new', environ={remote.RESUME: 'stale'})
        self.assertNotIn(remote.RESUME, calls[0][2])


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
