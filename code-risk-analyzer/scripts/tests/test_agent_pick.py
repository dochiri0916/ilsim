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


class BoostTests(unittest.TestCase):
    def test_plenty_left_right_before_the_reset_gets_the_strongest_setting(self):
        self.assertTrue(pick.choose(FREE, codex(40, 10), NOW)[2])     # 60% left, 10 minutes
        self.assertFalse(pick.choose(FREE, codex(90, 10), NOW)[2])    # 10% left: spend it, normal setting
        self.assertFalse(pick.choose(FREE, codex(40, 200), NOW)[2])   # plenty but a long time left

    def test_settings_come_from_the_tier_route_of_the_chosen_cli(self):
        routing = pick._module('ask').default_routing({})
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
                          '-m', 'gpt-6.1-sol', '-c', 'model_reasoning_effort="high"', '-'], argv)
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

    def test_why_and_no_task_only_print(self):
        self.assertEqual(0, self.main('--why'))
        self.assertEqual(0, self.main())
        self.assertEqual([], self.started)
        self.assertTrue(any('사용법' in line for line in self.lines))


if __name__ == '__main__':
    unittest.main()
