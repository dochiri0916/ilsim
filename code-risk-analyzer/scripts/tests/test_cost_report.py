import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('cost_report', Path(__file__).resolve().parents[1] / 'cost_report.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


def attempt(attempt_id, status, provider='codex', model='small', effort='low', tokens=(100, 50, 10), cost=None, seconds=2.0):
    usage = {'inputTokens': tokens[0], 'cachedInputTokens': tokens[1], 'outputTokens': tokens[2]}
    if cost is not None:
        usage['costUsd'] = cost
    return {'number': 1, 'status': status, 'failureKind': '' if status == 'COMPLETED' else 'OUTPUT_CONTRACT',
            'attemptId': attempt_id, 'provider': provider, 'model': model, 'reasoningEffort': effort,
            'usage': usage, 'durationSeconds': seconds}


class CostReportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def session(self, name, phase, artifacts, repairs=0):
        directory = self.root/name
        directory.mkdir()
        state = {'version': 1, 'phase': phase, 'history': []}
        if repairs:
            state['repair'] = {'iterations': repairs, 'modelCalls': repairs}
        (directory/'state.json').write_text(json.dumps(state))
        for relative, attempts in artifacts.items():
            (directory/relative).parent.mkdir(parents=True, exist_ok=True)
            (directory/relative).write_text(json.dumps({'status': attempts[-1]['status'], 'attempts': attempts}))
        return directory

    def test_reports_tokens_retries_and_cost_per_successful_task_by_model(self):
        first = attempt('a1', 'INVALID_OUTPUT')
        second = attempt('a2', 'COMPLETED', provider='claude', model='sonnet', effort='medium', tokens=(400, 0, 60), cost=0.02)
        done = self.session('done', 'COMPLETED', {
            'agent-test-writing.json': [first, second],
            'agent-implementation.json': [attempt('a3', 'COMPLETED')],
            # Repair archives copy earlier artifacts; the same attempt must not be counted twice.
            'repair-1/agent-implementation.json': [attempt('a3', 'COMPLETED')]}, repairs=1)
        failed = self.session('failed', 'STALE', {'agent-test-writing.json': [attempt('b1', 'TIMED_OUT', seconds=5.0)]})

        result = report.build([done, failed])

        self.assertEqual({'sessions': 2, 'completedSessions': 1, 'attempts': 4, 'acceptedAttempts': 2, 'retries': 2,
                          'repairIterations': 1, 'inputTokens': 700, 'cachedInputTokens': 150, 'outputTokens': 90,
                          'costUsd': 0.02, 'durationSeconds': 11.0, 'tokensPerCompletedSession': 790.0},
                         result['total'])
        small = result['models']['codex/small/low']
        self.assertEqual(3, small['attempts'])
        self.assertEqual(1, small['acceptedAttempts'])
        self.assertEqual(2, small['sessions'])
        self.assertEqual(1, small['completedSessions'])
        self.assertEqual(330.0, small['tokensPerCompletedSession'])
        self.assertEqual(0.02, result['models']['claude/sonnet/medium']['costPerCompletedSession'])

    def test_unknown_usage_is_reported_rather_than_counted_as_zero(self):
        legacy = {'number': 1, 'status': 'COMPLETED', 'failureKind': '', 'attemptId': 'old'}
        result = report.build([self.session('legacy', 'COMPLETED', {'agent-implementation.json': [legacy]})])
        self.assertEqual(1, result['total']['attemptsWithoutUsage'])
        self.assertIsNone(result['total']['tokensPerCompletedSession'])
        self.assertIn('unknown/unknown/default', result['models'])

    def test_rejects_directories_without_session_state(self):
        with self.assertRaises(ValueError):
            report.build([self.root])


if __name__ == '__main__':
    unittest.main()
