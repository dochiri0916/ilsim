import json
import time
import unittest
from unittest.mock import patch
from scripts.tests import test_code_change_workflow as fixtures


controller = fixtures.controller


class RepairWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.CodeChangeWorkflowTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.agent_input()
        self.config = self.fixture.config
        self.config.update(repair={'maxIterations': 2, 'maxModelCalls': 3, 'timeoutSeconds': 60},
                           qualityGates={'targets': {'Service': 'src/main/Service.java'}})
        self.project, self.state = self.fixture.project, self.fixture.state
        self.put = self.fixture.put
        self.phase = self.fixture.run_stage
        self.scan = patch.object(controller.Controller, '_scan', side_effect=lambda: self.put('build/session/scan.json', '{"findings": []}'))
        self.scan.start()
        self.addCleanup(self.scan.stop)
        self.gate = patch.object(controller.Controller, '_quality_evidence', side_effect=self.quality)
        self.gate.start()
        self.addCleanup(self.gate.stop)
        self.phase('baseline')
        with patch.object(controller.Controller, '_write', side_effect=self.fixture.writer):
            for stage, path in (('test-writing', 'src/test/ServiceTest.java'), ('implementation', 'src/main/Service.java')):
                changes = self.fixture.changes(path)
                changes['changes'][0]['content'] = (self.project/path).read_text()
                self.phase(stage, changes=changes)

    def quality(self, after):
        self.put('build/session/quality.json', '{"status": "PASSED"}')
        self.put('build/session/mutation.json', '{}')
        return {'status': 'PASSED', 'failures': []}

    def failing_snapshot(self):
        real = controller.regression.snapshot(self.project, controller.regression.read_json(self.project/'plan.json'), coverage=True)
        if 'fixed' not in (self.project/'src/main/Service.java').read_text():
            real.update(buildExitCode=1, tests={'ServiceTest#works': 'FAILED'},
                        testFailures=[{'test': 'ServiceTest#works', 'kind': 'ASSERTION', 'locations': []}])
        return real

    def generated(self, fixed=True):
        return {'status': 'COMPLETED', 'output': {'status': 'COMPLETED',
                'changes': [{'path': 'src/main/Service.java', 'content': 'class Service { /* fixed */ }'}] if fixed else [],
                'requirementTestMap': [], 'expectedFailures': [], 'unknowns': []}}

    def repair(self, generated=None, snapshot=None):
        original = controller.regression.snapshot
        with patch.object(controller.regression, 'snapshot', side_effect=snapshot or (lambda *a, **k: self.snapshot_using(original))), \
                patch.object(controller.agent, 'run', return_value=generated or self.generated()) as run, \
                patch.object(controller.Controller, '_write', side_effect=self.fixture.writer):
            result = self.phase('repair')
        return result, run

    def snapshot_using(self, original):
        with patch.object(controller.regression, 'snapshot', original):
            return self.failing_snapshot()

    def test_assertion_repair_reverifies_and_preserves_tests_with_auditable_history(self):
        # given
        tests = controller.regression.digest(self.project/'src/test/ServiceTest.java')
        # when
        result, run = self.repair()
        # then
        self.assertEqual('PASSED', result['status'])
        run.assert_called_once()
        self.assertIn('ASSERTION', run.call_args.args[2])
        self.assertEqual(tests, controller.regression.digest(self.project/'src/test/ServiceTest.java'))
        state = controller.regression.read_json(self.state)
        self.assertEqual('VERIFIED', state['phase'])
        self.assertEqual(['ASSERTION', 'PASSED'], [item['classification']['kind'] for item in state['repair']['history']])
        self.assertEqual(1, state['repair']['modelCalls'])
        self.assertTrue((self.state.parent/'repair-1/regression.json').is_file())

    def test_noop_repairs_stop_at_iteration_limit_and_resume_cannot_reset_budget(self):
        # when
        result, run = self.repair(self.generated(False))
        # then
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertEqual(2, run.call_count)
        self.assertIn('iteration', result['reason'])
        resumed, run = self.repair(self.generated(False))
        self.assertEqual('NEEDS_REVIEW', resumed['status'])
        run.assert_not_called()
        self.assertEqual(2, controller.regression.read_json(self.state)['repair']['iterations'])

    def test_environment_and_missing_evidence_failures_do_not_spend_model_calls(self):
        original = controller.regression.snapshot
        after = original(self.project, controller.regression.read_json(self.project/'plan.json'), coverage=True)
        after.update(buildExitCode=1, tests={}, executionFailure=controller.failure.outcome('ENVIRONMENT'))
        # when
        result, run = self.repair(snapshot=lambda *a, **k: after)
        # then
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertIn('ENVIRONMENT', result['reason'])
        run.assert_not_called()

    def test_quality_failure_blocks_verification_without_mutating_accepted_tests(self):
        after = controller.regression.snapshot(self.project, controller.regression.read_json(self.project/'plan.json'), coverage=True)
        with patch.object(controller.Controller, '_quality_evidence', return_value={'status': 'FAILED', 'failures': ['Surviving mutation']}):
            result, run = self.repair(snapshot=lambda *a, **k: after)
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertIn('QUALITY', result['reason'])
        run.assert_not_called()
        self.assertNotIn('verified', controller.regression.read_json(self.state))

    def test_expired_budget_and_model_budget_stop_before_provider_invocation(self):
        self.repair(self.generated(False))
        state = controller.regression.read_json(self.state)
        state['repair']['startedAt'] = time.time()-61
        controller.regression.save(self.state, state)
        result, run = self.repair()
        self.assertIn('time budget', result['reason'])
        run.assert_not_called()

    def test_repair_requires_quality_targets_and_rejects_test_or_scope_changes(self):
        config = dict(self.config)
        config.pop('qualityGates')
        with self.assertRaises(ValueError): controller.Controller(config, self.state)
        generated = self.generated()
        generated['output']['changes'][0]['path'] = 'src/test/ServiceTest.java'
        with self.assertRaises(ValueError): self.repair(generated)
        self.assertEqual('class ServiceTest {}', (self.project/'src/test/ServiceTest.java').read_text())

    def test_consumed_model_budget_stops_before_another_external_call(self):
        # given: a persisted attempt already consumed the call budget.
        state = controller.regression.read_json(self.state)
        state['repair'] = dict(controller.repair_settings(self.config['repair']), startedAt=time.time(),
                               modelCalls=3, iterations=0, history=[],
                               protected=controller.regression.protected_sources(state['sources']))
        controller.regression.save(self.state, state)
        # when
        result, run = self.repair()
        # then
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertIn('model-call budget', result['reason'])
        run.assert_not_called()

    def test_input_drift_on_a_failed_build_invalidates_before_spending_a_call(self):
        original = controller.regression.snapshot
        def changed(*args, **kwargs):
            result = self.snapshot_using(original)
            self.put('docs/spec.md', 'A changed requirement during failed verification')
            return result
        # when / then
        with patch.object(controller.regression, 'snapshot', side_effect=changed), patch.object(controller.agent, 'run') as run:
            with self.assertRaises(ValueError): self.phase('repair')
            run.assert_not_called()
        state = controller.regression.read_json(self.state)
        self.assertEqual('INPUTS', state['staleKind'])
        self.assertNotIn('verified', state)

    def test_ambiguous_static_evidence_is_classified_and_never_repaired(self):
        after = controller.regression.snapshot(self.project, controller.regression.read_json(self.project/'plan.json'), coverage=True)
        with patch.object(controller.Controller, '_scan', side_effect=ValueError('Ambiguous static evidence identifier')):
            result, run = self.repair(snapshot=lambda *a, **k: after)
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertIn('EVIDENCE', result['reason'])
        run.assert_not_called()
        self.assertNotIn('verified', controller.regression.read_json(self.state))


if __name__ == '__main__':
    unittest.main()
