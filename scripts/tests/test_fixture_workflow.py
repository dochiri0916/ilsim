import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

directory = Path(__file__).resolve().parents[2]/'integration-fixtures/spring-risk-sample/scripts'
sys.path.insert(0, str(directory))
try:
    spec = importlib.util.spec_from_file_location('fixture_workflow_test_target', directory/'verify_workflow.py')
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)
finally:
    sys.path.pop(0)


class FixtureWorkflowTests(unittest.TestCase):
    def test_container_startup_failure_cleans_only_generated_container(self):
        summary = {}
        calls = [subprocess.CalledProcessError(1, 'docker'), subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 1)]
        with patch.object(fixture.subprocess, 'run', side_effect=calls) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                with fixture.mysql(summary): self.fail('Failed startup must not yield')
        start, cleanup, inspect = [c.args[0] for c in run.call_args_list]
        owned = start[start.index('--name')+1]
        self.assertRegex(owned, r'^code-risk-sample-[0-9a-f]{12}$')
        self.assertEqual(['docker', 'rm', '--force', owned], cleanup)
        self.assertEqual(['docker', 'inspect', owned], inspect)
        self.assertTrue(summary['mysqlContainerStopped'])

    def test_document_mode_rejects_before_container_or_snapshot(self):
        with patch.object(sys, 'argv', ['verify_workflow.py', '--mode', 'SPEC_ONLY']), \
             patch.object(fixture, 'mysql') as database, \
             patch.object(fixture.workflow, 'source_hashes') as hashes:
            self.assertEqual(2, fixture.main())
        database.assert_not_called(); hashes.assert_not_called()

    def test_rejected_recovery_still_restores_only_owned_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary).resolve()
            source = project/'src/main/java/com/example/risksample/PricePolicy.java'
            source.parent.mkdir(parents=True)
            original = 'return quantity >= 10 ? 10 : 0;\n'
            source.write_text(original)
            session = fixture.Session(project/'build/session', {})
            def run(stage, *options, **kwargs):
                if stage == 'implementation':
                    source.write_text(original.replace('? 10 :', '? 11 :'))
                if stage == 'verification':
                    fixture.workflow.save(session.directory/'regression.json', {'status': 'FAILED', 'failures': ['appliesDiscountAtTheExactBoundary FAILED']})
                if stage == 'test-writing' and source.read_text() != original:
                    raise ValueError('Recovery request rejected')
            with patch.object(fixture, 'PROJECT', project), patch.object(session, 'run', side_effect=run):
                with self.assertRaises(ValueError): session.failures('owned')
            self.assertEqual(original, source.read_text())


if __name__ == '__main__': unittest.main()
