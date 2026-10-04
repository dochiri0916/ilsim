from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('usage_report', SCRIPTS/'usage_report.py')
usage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(usage)

NOW = datetime(2026, 10, 4, 12, 0, tzinfo=timezone.utc)


def stamp(hours_ago):
    return (NOW - timedelta(hours=hours_ago)).isoformat().replace('+00:00', 'Z')


def write(path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('\n'.join(json.dumps(entry) for entry in entries) + '\nnot json\n')


def assistant(hours_ago, message_id, model='claude-sonnet-5-5', output=100):
    return {'type': 'assistant', 'timestamp': stamp(hours_ago), 'requestId': 'req-' + message_id,
            'message': {'id': message_id, 'model': model, 'usage': {
                'input_tokens': 10, 'cache_read_input_tokens': 1000, 'cache_creation_input_tokens': 50, 'output_tokens': output}}}


def token_count(hours_ago, input_tokens, cached, output, limits=None):
    payload = {'type': 'token_count', 'info': {'total_token_usage': {
        'input_tokens': input_tokens, 'cached_input_tokens': cached, 'output_tokens': output}}}
    if limits:
        payload['rate_limits'] = limits
    return {'timestamp': stamp(hours_ago), 'type': 'event_msg', 'payload': payload}


class UsageReportTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def test_claude_messages_are_counted_once_per_period_and_model(self):
        # given: a streamed message logged twice, one older message and one outside the week
        write(self.root/'claude/projects/p/a.jsonl', [assistant(1, 'm1'), assistant(1, 'm1'), assistant(30, 'm2', output=5),
                                                      assistant(24 * 9, 'm3'), {'type': 'user', 'timestamp': stamp(1)}])
        # when
        value = usage.claude_usage(self.root/'claude', NOW)
        # then
        self.assertEqual({'input': 10, 'cachedInput': 1000, 'cacheWrite': 50, 'output': 100, 'requests': 1},
                         value['periods']['last5h']['claude-sonnet-5-5'])
        self.assertEqual(2, value['periods']['last7d']['claude-sonnet-5-5']['requests'])
        self.assertEqual(105, value['periods']['last7d']['claude-sonnet-5-5']['output'])

    def test_codex_cumulative_totals_become_per_request_deltas_with_the_latest_limits(self):
        # given: cumulative snapshots (one repeated) in a session that used gpt-6.1-sol
        limits = {'primary': {'used_percent': 42.0, 'window_minutes': 300, 'resets_in_seconds': 3600},
                  'secondary': {'used_percent': 7.5, 'window_minutes': 10080, 'resets_at': (NOW + timedelta(days=3)).timestamp()},
                  'plan_type': 'plus'}
        write(self.root/'codex/sessions/2026/10/04/rollout-1.jsonl', [
            {'timestamp': stamp(3), 'type': 'turn_context', 'payload': {'model': 'gpt-6.1-sol'}},
            token_count(3, 1000, 600, 50),
            token_count(2, 1000, 600, 50),
            token_count(1, 3000, 2000, 120, limits)])
        # when
        value = usage.codex_usage(self.root/'codex', NOW)
        # then: input excludes cached input, and the repeated snapshot adds nothing
        self.assertEqual({'input': 1000, 'cachedInput': 2000, 'cacheWrite': 0, 'output': 120, 'requests': 2},
                         value['periods']['last5h']['gpt-6.1-sol'])
        self.assertEqual(42.0, value['limits']['fiveHour']['usedPercent'])
        self.assertEqual((NOW - timedelta(hours=1) + timedelta(seconds=3600)).isoformat(), value['limits']['fiveHour']['resetsAt'])
        text = usage.report(usage.claude_usage(self.root/'claude', NOW), value, NOW)
        self.assertIn('5시간: 42% 사용 (기록 이후 이미 초기화됨)', text)
        self.assertIn('주간: 8% 사용', text)
        self.assertIn('요금제 plus', text)
        self.assertIn('기록 파일이 없습니다 (~/.claude/projects)', text)

    def test_main_reads_the_configured_homes_and_spends_nothing(self):
        write(self.root/'claude/projects/p/a.jsonl', [assistant(1, 'm1')])
        with redirect_stdout(io.StringIO()) as output:
            code = usage.main([], {'CLAUDE_CONFIG_DIR': str(self.root/'claude'), 'CODEX_HOME': str(self.root/'none'),
                                   'XDG_STATE_HOME': str(self.root/'state')}, NOW)
        self.assertEqual(0, code)
        self.assertIn('claude-sonnet-5-5', output.getvalue())
        self.assertIn('기록 파일이 없습니다 (~/.codex/sessions)', output.getvalue())
        self.assertIn('한도 기록이 아직 없습니다 (Claude CLI에 로그인돼 있으면 ai --usage가 받아 옵니다)', output.getvalue())

    def test_claude_limits_from_rate_limit_events_are_merged_and_reported(self):
        # given: claude -p reports both windows, later only the weekly one at the top level
        path = usage.claude_limits_file({'XDG_STATE_HOME': str(self.root/'state')})
        both = {'status': 'allowed', 'rateLimitType': 'five_hour', 'utilization': 0.02,
                'unifiedWindows': {'five_hour': {'utilization': 0.25, 'resetsAt': int((NOW + timedelta(hours=2)).timestamp())},
                                   'seven_day': {'utilization': 0.5, 'resetsAt': int((NOW + timedelta(days=3)).timestamp())}}}
        weekly = {'status': 'allowed_warning', 'rateLimitType': 'seven_day', 'utilization': 0.77,
                  'resetsAt': int((NOW + timedelta(days=3)).timestamp())}
        # when
        self.assertTrue(usage.save_claude_limits(path, both, NOW - timedelta(minutes=30)))
        self.assertTrue(usage.save_claude_limits(path, weekly, NOW - timedelta(minutes=10)))
        self.assertFalse(usage.save_claude_limits(path, {'status': 'allowed'}, NOW))
        # then: the five-hour window keeps its earlier value, the weekly one is replaced
        limits = usage.load_claude_limits(path)
        self.assertEqual(25.0, limits['fiveHour']['usedPercent'])
        self.assertEqual(77.0, limits['weekly']['usedPercent'])
        self.assertEqual((NOW - timedelta(minutes=10)).isoformat(), limits['observedAt'])
        claude = dict(usage.claude_usage(self.root/'claude', NOW), limits=limits)
        text = usage.report(claude, usage.codex_usage(self.root/'codex', NOW), NOW)
        self.assertIn('5시간: 25% 사용', text)
        self.assertIn('주간: 77% 사용', text)

    def test_a_rejected_window_without_utilization_counts_as_used_up(self):
        found = usage.claude_limits_from({'status': 'rejected', 'rateLimitType': 'five_hour',
                                          'resetsAt': int((NOW + timedelta(hours=1)).timestamp()) * 1000}, NOW)
        self.assertEqual(100.0, found['fiveHour']['usedPercent'])
        self.assertEqual((NOW + timedelta(hours=1)).isoformat(), found['fiveHour']['resetsAt'])


if __name__ == '__main__':
    unittest.main()
