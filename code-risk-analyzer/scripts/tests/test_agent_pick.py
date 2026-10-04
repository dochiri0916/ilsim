from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timedelta, timezone
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('agent_pick', Path(__file__).resolve().parents[1]/'agent_pick.py')
pick = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pick)

NOW = datetime(2026, 10, 4, 12, 0, tzinfo=timezone.utc)
FREE = {'blockedUntil': None, 'windowResetsAt': None}


def codex(five_used, five_minutes, week_used=10, week_hours_left=150):
    return {'fiveHour': {'usedPercent': five_used, 'resetsAt': (NOW + timedelta(minutes=five_minutes)).isoformat()},
            'weekly': {'usedPercent': week_used, 'resetsAt': (NOW + timedelta(hours=week_hours_left)).isoformat()}}


class ChooseTests(unittest.TestCase):
    def test_little_left_but_resetting_soon_is_spent_first(self):
        # given: Codex has 10% left and resets in 7 minutes
        name, why, _ = pick.choose(FREE, codex(90, 7), NOW)
        # then: unused, it would be lost at the reset
        self.assertEqual('codex', name)
        self.assertIn('10% 남음, 7분 뒤 초기화', why)

    def test_little_left_with_a_long_wait_goes_to_claude(self):
        self.assertEqual('claude', pick.choose(FREE, codex(90, 200), NOW)[0])

    def test_a_used_up_or_blocked_cli_is_skipped(self):
        self.assertEqual('claude', pick.choose(FREE, codex(99, 5), NOW)[0])
        blocked = {'blockedUntil': NOW + timedelta(minutes=40), 'windowResetsAt': None}
        name, why, _ = pick.choose(blocked, codex(70, 200), NOW)
        self.assertEqual(('codex', True), (name, '40분 뒤 해제' in why))
        self.assertEqual(None, pick.choose(blocked, codex(99, 25), NOW)[0])

    def test_claude_window_ending_soon_is_spent_first(self):
        soon = {'blockedUntil': None, 'windowResetsAt': NOW + timedelta(minutes=10)}
        self.assertEqual('claude', pick.choose(soon, codex(20, 200), NOW)[0])

    def test_codex_ahead_of_its_weekly_pace_is_spared(self):
        # 70% of the week used with only about half of it gone
        name, why, _ = pick.choose(FREE, codex(10, 200, week_used=70, week_hours_left=84), NOW)
        self.assertEqual('claude', name)
        self.assertIn('주간', why)
        self.assertEqual('codex', pick.choose(FREE, codex(10, 200, week_used=40, week_hours_left=84), NOW)[0])

    def test_a_limit_past_its_reset_counts_as_full_and_missing_clis_are_not_chosen(self):
        self.assertEqual('codex', pick.choose(FREE, codex(100, -5), NOW)[0])
        self.assertEqual('claude', pick.choose(FREE, codex(10, 200), NOW, installed=('claude',))[0])
        self.assertEqual('codex', pick.choose(FREE, None, NOW)[0])


def claude(five_left, five_minutes, week_left=90, week_hours_left=150):
    return {'blockedUntil': None, 'windowResetsAt': NOW + timedelta(minutes=five_minutes), 'fiveLeft': five_left,
            'weekLeft': week_left, 'weekResetsAt': NOW + timedelta(hours=week_hours_left)}


class RecordedClaudeTests(unittest.TestCase):
    def test_claude_resetting_soon_is_spent_only_with_room_left(self):
        name, why, boost = pick.choose(claude(60, 10), codex(20, 200), NOW)
        self.assertEqual(('claude', True), (name, boost))
        self.assertIn('Claude 5시간 한도 60% 남음, 10분 뒤 초기화', why)
        self.assertFalse(pick.choose(claude(10, 10), codex(90, 200), NOW)[2])         # spend 10%, normal setting
        self.assertEqual('codex', pick.choose(claude(2, 10), codex(20, 200), NOW)[0])  # nothing worth spending

    def test_codex_is_spared_only_for_a_claude_that_is_better_off(self):
        # Codex 70% used at mid-week; Claude even further ahead of its pace keeps Codex
        self.assertEqual('claude', pick.choose(claude(80, 200, week_left=60, week_hours_left=84), codex(10, 200, 70, 84), NOW)[0])
        self.assertEqual('codex', pick.choose(claude(80, 200, week_left=10, week_hours_left=84), codex(10, 200, 70, 84), NOW)[0])
        # Codex 5-hour room is low, but Claude's is lower
        self.assertEqual('claude', pick.choose(claude(50, 200), codex(90, 200), NOW)[0])
        self.assertEqual('codex', pick.choose(claude(5, 200), codex(90, 200), NOW)[0])

    def test_recorded_limits_override_the_estimate_until_they_reset(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        recorded = {'fiveHour': {'usedPercent': 99.0, 'resetsAt': (NOW + timedelta(minutes=40)).isoformat()},
                    'weekly': {'usedPercent': 30.0, 'resetsAt': (NOW + timedelta(days=2)).isoformat()}}
        state = pick.claude_state(temp.name, NOW, recorded)
        self.assertEqual(NOW + timedelta(minutes=40), state['blockedUntil'])
        self.assertEqual((70.0, NOW + timedelta(days=2)), (state['weekLeft'], state['weekResetsAt']))
        recorded['fiveHour']['resetsAt'] = (NOW - timedelta(minutes=1)).isoformat()
        state = pick.claude_state(temp.name, NOW, recorded)
        self.assertEqual((None, None, None), (state['blockedUntil'], state['windowResetsAt'], state['fiveLeft']))


class BoostTests(unittest.TestCase):
    def test_plenty_left_right_before_the_reset_gets_the_strongest_setting(self):
        self.assertTrue(pick.choose(FREE, codex(40, 10), NOW)[2])     # 60% left, 10 minutes
        self.assertFalse(pick.choose(FREE, codex(90, 10), NOW)[2])    # 10% left: spend it, normal setting
        self.assertFalse(pick.choose(FREE, codex(40, 200), NOW)[2])   # plenty but a long time left

    def test_settings_come_from_the_tier_route_of_the_chosen_cli(self):
        routing = pick.default_routing({})
        self.assertEqual(('gpt-6.1-sol', 'xhigh'), pick.launch_setting('codex', 'TIER_4', routing))
        self.assertEqual(('claude-sonnet-5-5', 'xhigh'), pick.launch_setting('claude', 'TIER_4', routing))
        self.assertEqual(('gpt-6.1-sol', 'high'), pick.launch_setting('codex', 'TIER_3', routing))
        self.assertEqual((None, None), pick.launch_setting('codex', None, routing))


class ClaudeStateTests(unittest.TestCase):
    def write(self, entries):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        path = Path(temp.name)/'projects'/'p'/'s.jsonl'
        path.parent.mkdir(parents=True)
        path.write_text('\n'.join(json.dumps(entry) for entry in entries))
        return temp.name

    def message(self, minutes_ago, text=None, error=False):
        entry = {'type': 'assistant', 'timestamp': (NOW - timedelta(minutes=minutes_ago)).isoformat(),
                 'message': {'usage': {'input_tokens': 1}, 'content': [{'type': 'text', 'text': text or 'ok'}]}}
        if error:
            entry['isApiErrorMessage'] = True
            del entry['message']['usage']
        return entry

    def test_window_starts_at_the_first_message_after_the_last_window(self):
        # 400 minutes ago opened a window that ended 100 minutes ago; 290 minutes ago... is inside it
        root = self.write([self.message(400), self.message(290), self.message(90), self.message(10)])
        self.assertEqual(NOW + timedelta(minutes=210), pick.claude_state(root, NOW)['windowResetsAt'])

    def test_limit_message_blocks_until_its_reset(self):
        epoch = int((NOW + timedelta(minutes=45)).timestamp())
        root = self.write([self.message(30), self.message(5, f'Claude AI usage limit reached|{epoch}', error=True)])
        self.assertEqual(NOW + timedelta(minutes=45), pick.claude_state(root, NOW)['blockedUntil'])
        old = self.write([self.message(400, 'Claude AI usage limit reached|%d' % int((NOW - timedelta(hours=1)).timestamp()), error=True)])
        self.assertIsNone(pick.claude_state(old, NOW)['blockedUntil'])


class FakeProcess:
    def __init__(self, lines, code=0, stderr=''):
        self.stdin, self.stdout, self.stderr, self.code = io.StringIO(), iter(lines), io.StringIO(stderr), code

    def wait(self):
        return self.code


def events(*items):
    return [json.dumps(item) + '\n' for item in items]


CLAUDE_OK = events({'type': 'system', 'subtype': 'init', 'session_id': 'c-1'},
                   {'type': 'assistant', 'message': {'content': [{'type': 'tool_use', 'name': 'Edit', 'input': {'file_path': 'src/A.java'}},
                                                                  {'type': 'text', 'text': '수정했습니다'}]}},
                   {'type': 'result', 'is_error': False, 'result': '수정했습니다', 'session_id': 'c-1'})
CODEX_OK = events({'type': 'thread.started', 'thread_id': 'x-1'},
                  {'type': 'item.completed', 'item': {'type': 'command_execution', 'command': './gradlew test'}},
                  {'type': 'item.completed', 'item': {'type': 'agent_message', 'text': '테스트 통과'}},
                  {'type': 'turn.completed'})


class MainTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.home = temp.name
        self.environ = {'CLAUDE_CONFIG_DIR': self.home, 'CODEX_HOME': self.home, 'XDG_STATE_HOME': self.home}
        self.lines, self.started, self.outputs = [], [], []

    def popen(self, argv, **options):
        self.started.append((argv, options['cwd']))
        return self.outputs.pop(0)

    def main(self, *argv, now=NOW):
        return pick.main(list(argv), environ=self.environ, now=now, which=lambda cli: '/bin/' + cli, popen=self.popen,
                         cwd=self.home, say=self.lines.append)

    def test_a_task_runs_headless_in_the_folder_and_prints_progress(self):
        # given: no usage recorded, so Codex is chosen
        self.outputs = [FakeProcess(CODEX_OK)]
        # when
        code = self.main('결제', '동시성', '버그', '고쳐줘')
        # then: no interactive screen, the task goes to stdin, progress lines are shown
        argv, cwd = self.started[0]
        self.assertEqual(0, code)
        self.assertEqual(['codex', 'exec', '--json', '--skip-git-repo-check', '--sandbox', 'workspace-write',
                          '-c', 'mcp_servers={}', '-m', 'gpt-6.1-sol', '-c', 'model_reasoning_effort="high"', '-'], argv)
        self.assertEqual(Path(self.home).resolve(), cwd)
        self.assertIn('  · 명령 ./gradlew test', self.lines)
        self.assertIn('테스트 통과', self.lines)

    def test_the_next_task_continues_the_conversation_unless_new(self):
        self.outputs = [FakeProcess(CLAUDE_OK), FakeProcess(CLAUDE_OK), FakeProcess(CLAUDE_OK)]
        self.main('claude', '회원', '탈퇴', '추가해줘')
        self.main('claude', '테스트도', '추가해줘', now=NOW + timedelta(minutes=20))
        self.main('claude', '--new', '다른', '일', now=NOW + timedelta(minutes=30))
        resumed = [argv[argv.index('--resume') + 1] if '--resume' in argv else None for argv, _ in self.started]
        self.assertEqual([None, 'c-1', None], resumed)
        self.assertIn('  · Edit src/A.java', self.lines)

    def test_hitting_a_limit_hands_the_task_to_the_other_cli(self):
        limited = events({'type': 'system', 'subtype': 'init', 'session_id': 'c-2'},
                         {'type': 'result', 'is_error': True, 'result': 'Claude AI usage limit reached', 'session_id': 'c-2'})
        self.outputs = [FakeProcess(limited, code=1), FakeProcess(CODEX_OK)]
        with patch.object(pick, 'choose', return_value=('claude', 'test', False)):
            code = self.main('작업해줘')
        self.assertEqual(0, code)
        self.assertEqual(['claude', 'codex'], [argv[0] for argv, _ in self.started])
        self.assertTrue(any('codex로 같은 작업' in line for line in self.lines))

    def test_failed_checks_go_to_the_next_task_once(self):
        prompts = []

        def popen(argv, **options):
            process = FakeProcess(CLAUDE_OK)
            process.stdin = type('Stdin', (), {'write': lambda _, text: prompts.append(text), 'close': lambda _: None})()
            return process
        failures = [['위험 분석: HIGH·CRITICAL 1건', '  - A.java:14 CON-001'], [], []]
        with patch.object(pick, 'checks', side_effect=lambda *args, **kwargs: failures.pop(0)):
            for minute, task in ((0, '추가해줘'), (5, '검사 결과 고쳐줘'), (10, '다음 일')):
                pick.main(['claude', task], environ=self.environ, now=NOW + timedelta(minutes=minute),
                          which=lambda cli: '/bin/' + cli, popen=popen, cwd=self.home, say=self.lines.append)
        self.assertEqual('추가해줘', prompts[0])
        self.assertIn('A.java:14 CON-001', prompts[1])
        self.assertTrue(prompts[1].endswith('요청: 검사 결과 고쳐줘'))
        self.assertEqual('다음 일', prompts[2])

    def test_mcp_is_off_and_answers_are_asked_to_be_short_unless_mcp_is_requested(self):
        claude = pick.command('claude', None, None, None)
        self.assertIn('--strict-mcp-config', claude)
        self.assertEqual(pick.CONCISE, claude[claude.index('--append-system-prompt') + 1])
        self.assertNotIn('--strict-mcp-config', pick.command('claude', None, None, None, mcp=True))
        self.assertNotIn('mcp_servers={}', pick.command('codex', None, None, None, mcp=True))
        self.assertTrue(pick._prompt('codex', '작업').startswith(pick.CONCISE))
        self.assertEqual('작업', pick._prompt('claude', '작업'))
        self.assertEqual((None, '작업', False, False, True), pick._split(['--mcp', '작업']))

    def test_a_task_updates_the_tool_first_and_why_does_not(self):
        updates = []
        self.outputs = [FakeProcess(CODEX_OK)]
        for argv in (['작업해줘'], ['--why'], []):
            pick.main(argv, environ=self.environ, now=NOW, which=lambda cli: '/bin/' + cli, popen=self.popen,
                      cwd=self.home, say=self.lines.append, update=updates.append)
        self.assertEqual([['작업해줘']], updates)

    def test_codex_resume_keeps_every_option_before_the_subcommand(self):
        argv = pick.command('codex', 'gpt-6.1-sol', 'medium', 'thread-1')
        self.assertEqual(['resume', 'thread-1', '-'], argv[-3:])
        self.assertLess(argv.index('--json'), argv.index('resume'))
        self.assertLess(argv.index('-m'), argv.index('resume'))

    def test_a_failed_resume_says_why_and_starts_new(self):
        failed = events({'type': 'error', 'message': "error: unexpected argument '--json' found"})
        self.outputs = [FakeProcess(CODEX_OK), FakeProcess(failed, code=2), FakeProcess(CODEX_OK)]
        self.main('codex', '첫', '일')
        self.main('codex', '다음', '일', now=NOW + timedelta(minutes=5))
        self.assertTrue(any("unexpected argument '--json'" in line for line in self.lines))
        self.assertIn('resume', self.started[1][0])
        self.assertNotIn('resume', self.started[2][0])

    def test_ctrl_c_stops_the_cli_keeps_the_conversation_and_names_changed_files(self):
        class Interrupted(FakeProcess):
            stopped = False

            def __init__(self):
                super().__init__([])
                self.stdout = self.lines()

            def lines(self):
                yield CLAUDE_OK[0]
                yield CLAUDE_OK[1]
                raise KeyboardInterrupt

            def terminate(self):
                Interrupted.stopped = True

            def wait(self, timeout=None):
                return -2
        self.outputs = [Interrupted(), FakeProcess(CLAUDE_OK)]
        with patch.object(pick, 'checks', side_effect=AssertionError('no checks after a cancel')):
            self.assertEqual(130, self.main('claude', '작업해줘'))
        self.assertTrue(Interrupted.stopped)
        self.assertTrue(any(line.startswith('ai: 취소했습니다 - 이미 바뀐 파일 1개 (src/A.java)') for line in self.lines))
        self.main('claude', '이어서', now=NOW + timedelta(minutes=1))
        self.assertEqual('c-1', self.started[1][0][self.started[1][0].index('--resume') + 1])

    def test_claude_limit_percentages_reported_while_working_are_recorded(self):
        # given: claude -p reports its windows during the task
        reset = int((NOW + timedelta(hours=3)).timestamp())
        self.outputs = [FakeProcess([CLAUDE_OK[0], json.dumps({'type': 'rate_limit_event', 'rate_limit_info': {
            'status': 'allowed', 'unifiedWindows': {'five_hour': {'utilization': 0.4, 'resetsAt': reset},
                                                    'seven_day': {'utilization': 0.2, 'resetsAt': reset + 86400}}}}) + '\n',
                                     *CLAUDE_OK[1:]])]
        # when
        with patch.object(pick, 'checks', return_value=[]):
            self.assertEqual(0, self.main('claude', '고쳐줘'))
        # then: not shown, but kept for ai --usage and the next choice
        self.assertFalse(any('rate_limit' in line for line in self.lines))
        limits = pick.usage.load_claude_limits(pick.usage.claude_limits_file(self.environ))
        self.assertEqual((40.0, 20.0), (limits['fiveHour']['usedPercent'], limits['weekly']['usedPercent']))
        self.assertIn('60% 남음', pick.choose(pick.claude_state(self.home, NOW + timedelta(hours=2, minutes=50), limits),
                                                 None, NOW + timedelta(hours=2, minutes=50))[1])

    def test_usage_fetches_claude_limits_with_one_tiny_request_only_when_stale(self):
        # given: no Claude limit record yet
        reset = int((NOW + timedelta(hours=1)).timestamp())
        self.outputs = [FakeProcess(events({'type': 'system', 'subtype': 'init', 'session_id': 'u-1'},
                                           {'type': 'rate_limit_event', 'rate_limit_info': {'unifiedWindows': {
                                               'five_hour': {'utilization': 0.35, 'resetsAt': reset},
                                               'seven_day': {'utilization': 0.6, 'resetsAt': reset + 86400}}}},
                                           {'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': 'ok'}]}},
                                           {'type': 'result', 'is_error': False, 'result': 'ok', 'session_id': 'u-1'}))]
        # when
        with redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, self.main('--usage'))
        # then: the cheapest model, no tools, no MCP, outside the project; its answer is not shown
        argv, cwd = self.started[0]
        self.assertEqual(['--model', 'haiku', '--tools', ''], argv[argv.index('--model'):argv.index('--model') + 4])
        self.assertIn('--strict-mcp-config', argv)
        self.assertNotEqual(self.home, cwd)
        self.assertIn('5시간: 35% 사용', output.getvalue())
        self.assertIn('주간: 60% 사용', output.getvalue())
        self.assertEqual([], self.lines)
        # and: a record younger than REFRESH_AFTER is shown without another request
        with redirect_stdout(io.StringIO()):
            self.main('--usage', now=NOW + timedelta(minutes=5))
        self.assertEqual(1, len(self.started))

    def test_usage_without_a_fresh_claude_answer_shows_the_last_record(self):
        self.outputs = [FakeProcess([], code=1, stderr='not logged in')]
        with redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, self.main('--usage'))
        self.assertIn('Claude Code 사용량', output.getvalue())
        self.assertEqual(['ai: Claude 한도를 새로 받지 못해 마지막 기록을 보여 줍니다 (not logged in)'], self.lines)
        with redirect_stdout(io.StringIO()):
            pick.main(['--usage'], environ=self.environ, now=NOW, which=lambda cli: None, popen=self.popen, say=self.lines.append)
        self.assertEqual(1, len(self.started))

    def test_why_and_no_task_only_print(self):
        self.assertEqual(0, self.main('--why'))
        self.assertEqual(0, self.main())
        self.assertEqual([], self.started)
        self.assertTrue(any('사용법' in line for line in self.lines))


class CheckTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.project = Path(temp.name).resolve()
        (self.project/'gradlew').write_text('')
        (self.project/'settings.gradle').write_text('')
        self.jar = self.project/'analyzer.jar'
        self.jar.write_text('')
        self.commands, self.lines = [], []

    def process(self, command, **options):
        self.commands.append(command)
        if 'scan' in command:
            output = self.project/command[command.index('--output') + 1]
            output.write_text(json.dumps({'findings': [
                {'ruleId': 'CON-001', 'severity': 'HIGH', 'status': 'REVIEW', 'title': 'Check-then-act race',
                 'location': {'file': 'src/main/java/A.java', 'startLine': 14}},
                {'ruleId': 'X', 'severity': 'MEDIUM', 'title': 'ignored', 'location': {}}]}))
            return pick.subprocess.CompletedProcess(command, 1, stdout='')
        return pick.subprocess.CompletedProcess(command, 0, stdout='컨벤션 검증: 통과\n리포트: x\n')

    def run_checks(self, task, edits=('src/main/java/A.java',)):
        pick.checks(task, self.project, list(edits), self.lines.append, self.process, self.jar)
        return [Path(command[1]).name if command[0] != 'java' else 'scan' for command in self.commands]

    def test_both_checks_run_after_a_change_and_report_briefly(self):
        self.assertEqual(['convention_check.py', 'scan'], self.run_checks('회원 탈퇴 추가해줘'))
        self.assertIn('컨벤션 검증: 통과', self.lines)
        self.assertIn('  - src/main/java/A.java:14 CON-001 Check-then-act race (review)', self.lines)
        self.assertIn('고치려면: ai 검사 결과 고쳐줘 (이 결과가 다음 요청에 한 번 함께 전달됨)', self.lines)
        self.assertEqual('src/main/java/A.java\n', (self.project/'build/ai/changed.txt').read_text())

    def test_jev_reviews_what_static_rules_could_not_confirm_within_a_cap(self):
        environments = []

        def process(command, **options):
            environments.append((command, options.get('env')))
            return self.process(command, **options)
        with tempfile.TemporaryDirectory() as secrets:
            (Path(secrets)/'keys').write_text('TYPESAFE_API_KEY=test-key\n')
            with patch.dict(pick.os.environ, {'CODE_RISK_SECRETS': secrets + '/keys'}, clear=False):
                pick.os.environ.pop('TYPESAFE_API_KEY', None)
                pick.os.environ.pop('CODE_RISK_JEV_MAX_CALLS', None)
                pick.checks('위험 분석만', self.project, ['src/main/java/A.java'], self.lines.append, process, self.jar)
        command, environ = next(item for item in environments if item[0][0] == 'java')
        self.assertEqual('true', command[command.index('--jev') + 1])
        self.assertEqual('20', environ['CODE_RISK_JEV_MAX_CALLS'])
        self.assertEqual('test-key', environ['TYPESAFE_API_KEY'])

    def test_without_a_key_the_review_items_are_named_as_unreviewed(self):
        with patch.dict(pick.os.environ, {'CODE_RISK_SECRETS': '/nonexistent'}, clear=False):
            pick.os.environ.pop('TYPESAFE_API_KEY', None)
            self.run_checks('추가해줘')
        self.assertTrue(any('JEV 키가 없어' in line for line in self.lines))
        self.assertIn('false', next(c for c in self.commands if c[0] == 'java'))

    def test_jev_opinion_is_shown_as_advice(self):
        report = {'findings': [{'ruleId': 'CON-001', 'severity': 'HIGH', 'status': 'REVIEW', 'title': 'race',
                                'location': {'file': 'A.java', 'startLine': 3},
                                'evidence': [{'type': 'JEV_RISK_REVIEW', 'attributes': {'riskProbability': 0.82}}]}],
                  'diagnostics': [{'code': 'JEV-002', 'message': 'limit'}]}
        lines = pick.risk_summary(report)
        self.assertIn('JEV 호출 한도 도달', lines[0])
        self.assertEqual('  - A.java:3 CON-001 race (review) JEV 위험 0.82', lines[1])

    def test_the_request_switches_off_only_the_named_check_for_this_command(self):
        cases = {'컨벤션 없이 추가해줘': ['scan'], '위험 분석 빼고 추가해줘': ['convention_check.py'],
                 '리스크 검사 하지 말고 해줘': ['convention_check.py'], '검사 없이 해줘': []}
        for task, expected in cases.items():
            with self.subTest(task=task):
                self.commands = []
                self.assertEqual(expected, self.run_checks(task))

    def test_nothing_runs_without_a_change_or_outside_a_gradle_project(self):
        self.assertEqual([], self.run_checks('설명해줘', edits=()))
        (self.project/'gradlew').unlink()
        self.assertEqual([], self.run_checks('추가해줘'))
        self.assertIn('검사: Gradle 프로젝트가 아니라 건너뜀', self.lines)

    def test_only_edited_production_java_files_are_scanned(self):
        edits = [str(self.project/'src/main/java/A.java'), 'src/test/java/ATest.java', '/elsewhere/B.java', 'README.md']
        self.assertEqual(['src/main/java/A.java'], pick.changed_sources(self.project, edits))


if __name__ == '__main__':
    unittest.main()
