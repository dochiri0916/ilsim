import importlib.util
from pathlib import Path
import signal
import subprocess
import unittest
from unittest.mock import Mock, patch

path = Path(__file__).resolve().parents[2]/'integration-fixtures/spring-risk-sample/scripts/verify_service.py'
spec = importlib.util.spec_from_file_location('fixture_service_test_target', path)
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


class FixtureServiceCleanupTests(unittest.TestCase):
    def run_failure(self, interrupt=False, escalation=False):
        process = Mock(pid=123456)
        # Alive during readiness, exited after process-group cleanup.
        process.poll.side_effect = [None, None, 0]
        process.wait.side_effect = [subprocess.TimeoutExpired('java', 15), 0] if escalation else None
        def launch(command, **kwargs):
            kwargs['stdout'].write('RISK_SAMPLE_READY_PORT=12345\n')
            kwargs['stdout'].flush()
            return process
        failure = KeyboardInterrupt() if interrupt else RuntimeError('HTTP contract failed')
        with patch.object(service.workflow, 'source_hashes', return_value={'source': 'hash'}), \
             patch.object(service.workflow, 'digest', return_value='jarhash'), \
             patch.object(service.workflow, 'save') as save, \
             patch.object(service.subprocess, 'Popen', side_effect=launch) as popen, \
             patch.object(service, 'urlopen', side_effect=failure), \
             patch.object(service.os, 'killpg') as kill:
            if interrupt:
                with self.assertRaises(KeyboardInterrupt): service.verify_service({}, 'owned')
                result = save.call_args.args[1]
            else:
                result = service.verify_service({}, 'owned')
        self.assertTrue(popen.call_args.kwargs['start_new_session'])
        kill.assert_any_call(process.pid, signal.SIGTERM)
        if escalation: kill.assert_any_call(process.pid, signal.SIGKILL)
        self.assertEqual('FAILED', result['status'])
        self.assertTrue(result['processStopped'])

    def test_http_failure_stops_entire_owned_process_group(self):
        self.run_failure()

    def test_interrupt_stops_process_before_propagating(self):
        self.run_failure(interrupt=True)

    def test_timeout_escalates_to_kill_and_records_cleanup(self):
        self.run_failure(escalation=True)


if __name__ == '__main__': unittest.main()
