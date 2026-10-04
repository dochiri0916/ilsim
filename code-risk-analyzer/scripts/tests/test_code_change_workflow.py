import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('code_change_workflow', Path(__file__).resolve().parents[1] / 'code_change_workflow.py')
controller = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controller)


class CodeChangeWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name).resolve()
        self.put('src/main/Service.java', 'class Service {}')
        self.put('src/test/ServiceTest.java', 'class ServiceTest {}')
        self.put('docs/spec.md', 'REQ-1 unchanged behavior')
        self.put('gradle/wrapper/gradle-wrapper.properties', 'version=9')
        self.put('gradlew', '''#!/bin/sh
mkdir -p build/test-results/test build/libs
echo '<testsuite><testcase classname="ServiceTest" name="works()"/></testsuite>' > build/test-results/test/TEST-ServiceTest.xml
echo artifact > build/libs/service.jar
''')
        (self.project/'gradlew').chmod(0o700)
        self.put('analyzer.jar', 'analyzer')
        self.put('requirements.json', json.dumps({'mode': 'CODE_CHANGE'}))
        self.put('scope.json', json.dumps({'project': str(self.project), 'files': ['src/main/Service.java', 'src/test/ServiceTest.java'],
                                         'actions': ['WRITE_TESTS', 'IMPLEMENT']}))
        self.put('plan.json', json.dumps({'mode': 'CODE_CHANGE', 'project': str(self.project),
            'approvedActions': ['BASELINE', 'REGRESSION', 'LOCAL_SERVICE', 'COMPLETE'],
            'features': {'existing': ['ServiceTest#works']}, 'newFeatures': {}, 'database': 'NOT_APPLICABLE'}))
        self.config = {name: str(self.project/path) for name, path in
                       [('plan', 'plan.json'), ('requirements', 'requirements.json'), ('scope', 'scope.json'), ('analyzer', 'analyzer.jar')]}
        self.config['documents'] = ['docs/spec.md']
        # Service evidence always comes from a controller-run adapter; each scenario is its own project script.
        for name in ('normal', 'duplicate', 'concurrency', 'rollback'):
            self.put('checks/' + name + '.py', "import os; open(os.environ['SCENARIO_REPORT'], 'w').write('{\"assertions\": 1, \"passed\": 1}')")
        self.config['serviceAdapter'] = {'jar': 'build/libs/service.jar', 'scenarios': {
            name: ['python3', 'checks/' + name + '.py'] for name in ('normal', 'duplicate', 'concurrency', 'rollback')}}
        self.state = self.project/'build/session/state.json'
        self.validation = patch.object(controller.regression, 'validate_requirements')
        self.validation.start()
        self.addCleanup(self.validation.stop)

    def put(self, path, text):
        target = self.project/path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def run_stage(self, stage, **kwargs):
        return controller.Controller(self.config, self.state).execute(stage, **kwargs)

    def writer(self, action, changes):
        for change in changes['changes']:
            self.put(change['path'], change['content'])
        return {'status': 'COMPLETED'}

    def changes(self, path):
        return {'changes': [{'path': path, 'expectedSha256': controller.regression.digest(self.project/path),
                             'content': (self.project/path).read_text() + '\n'}]}

    def implementation(self):
        self.run_stage('baseline')
        with patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.run_stage('test-writing', changes=self.changes('src/test/ServiceTest.java'))
            self.run_stage('implementation', changes=self.changes('src/main/Service.java'))

    def verify(self):
        def scan():
            self.put('build/session/scan.json', json.dumps(getattr(self, 'scan_report', {'findings': []})))
        with patch.object(controller.Controller, '_scan', side_effect=scan):
            # Existing test source modification needs a hash-bound contract review.
            baseline = controller.regression.read_json(self.project/'build/session/baseline.json')
            approval = {'path': 'src/test/ServiceTest.java', 'beforeSha256': baseline['protected']['src/test/ServiceTest.java'],
                'afterSha256': controller.regression.digest(self.project/'src/test/ServiceTest.java'),
                'basis': 'INCORRECT_TEST', 'reference': 'REQ-1', 'reviewer': 'reviewer', 'reason': 'Explicit test correction'}
            return self.run_stage('verification', approvals=[approval])

    def review(self, service_status='PASSED'):
        snap = controller.regression.read_json(self.project/'build/session/after.json')
        report = self.project/'build/session/scan.json'
        bundle = {'version': 1, 'snapshotSha256': controller.evidence.fingerprint(snap),
                  'reportSha256': controller.regression.digest(report), 'records': []}
        if 'serviceAdapter' in self.config:
            def adapter(project, config, sources, launcher):
                return {'status': service_status, 'adapter': 'GENERAL', 'sources': sources,
                        'jarSha256': controller.regression.digest(project/config['jar']), 'processStopped': True,
                        'scenarios': dict.fromkeys(['normal', 'duplicate', 'concurrency', 'rollback'], service_status)}
            with patch.object(controller.service_adapter, 'verify', side_effect=adapter):
                self.run_stage('local-service')
        return self.run_stage('review', bundle=bundle, open_evidence=[])

    def test_phase_order_and_missing_baseline_reject_before_execution(self):
        for stage in ('test-writing', 'implementation', 'verification', 'review', 'complete'):
            with self.subTest(stage=stage), patch.object(controller.regression, 'snapshot') as run:
                with self.assertRaises(ValueError): self.run_stage(stage)
                run.assert_not_called()
        self.run_stage('baseline')
        for stage in ('implementation', 'verification', 'review', 'complete'):
            with self.subTest(stage=stage), self.assertRaises(ValueError): self.run_stage(stage)

    def agent_input(self):
        requirement = {'scenarios': {'SCN-1': {'given': 'valid input', 'when': 'boundary call', 'expectedOutcomes': ['accepted']}},
                       'review': {'status': 'EVIDENCE_SUPPORTED', 'openQuestions': []}}
        requirement.update({name: {'applicability': 'NOT_APPLICABLE'} for name in
                            ('transaction', 'concurrency', 'idempotency', 'consistency')})
        self.put('requirements.json', json.dumps({'mode': 'CODE_CHANGE', 'requirements': {'REQ-1': requirement}}))
        self.config['agent'] = {'provider': 'codex', 'timeoutSeconds': 30}
        scope = controller.regression.read_json(self.project/'scope.json')
        scope['files'].append('src/test/NewTest.java')
        self.put('scope.json', json.dumps(scope))

    def generated(self, path='src/test/NewTest.java'):
        output = {'status': 'COMPLETED', 'changes': [{'path': path, 'content': 'class NewTest {}'}],
                  'requirementTestMap': [{'requirementId': 'REQ-1', 'scenarioId': 'SCN-1', 'tests': ['NewTest#boundary']}],
                  'expectedFailures': [{'test': 'NewTest#boundary', 'kind': 'ASSERTION', 'reason': 'Boundary incorrectly rejected'}],
                  'unknowns': []}
        return {'version': 1, 'attemptId': 'test-attempt', 'provider': 'codex', 'model': None, 'status': 'COMPLETED', 'output': output}

    def routing_input(self):
        self.agent_input()
        self.config.pop('agent')
        self.put('models.json', json.dumps({'version': 1, 'models': {
            'focused': {'adapter': 'codex', 'model': 'focused-model', 'status': 'ACTIVE',
                        'supportedTiers': ['TIER_2'], 'reasoningEfforts': ['medium', 'high']},
            'general': {'adapter': 'claude', 'model': 'general-model', 'status': 'ACTIVE',
                        'supportedTiers': ['TIER_3'], 'reasoningEfforts': ['medium', 'high']}}}))
        self.put('routing.json', json.dumps({'version': 1, 'tiers': {
            'TIER_2': {'modelId': 'focused', 'reasoningEffort': 'medium'},
            'TIER_3': {'modelId': 'general', 'reasoningEffort': 'high'}}}))
        self.config['routing'] = {'registry': str(self.project/'models.json'), 'policy': str(self.project/'routing.json'),
                                  'requiredTiers': {'test-writing': 'TIER_2', 'implementation': 'TIER_3'}, 'timeoutSeconds': 30}

    def routed_result(self, config, implementation=False):
        result = self.generated()
        if implementation:
            result['output'].update(changes=[], requirementTestMap=[], expectedFailures=[])
        result.update({key: config[key] for key in ('provider', 'model', 'reasoningEffort')})
        return result

    def automatic_input(self):
        self.routing_input()
        registry = controller.regression.read_json(self.project/'models.json')
        registry['models']['focused']['supportedTiers'].append('TIER_1')
        registry['models']['general']['supportedTiers'].append('TIER_4')
        self.put('models.json', json.dumps(registry))
        policy = controller.regression.read_json(self.project/'routing.json')
        policy['tiers'].update(TIER_1={'modelId': 'focused', 'reasoningEffort': 'medium'},
                               TIER_4={'modelId': 'general', 'reasoningEffort': 'high'})
        self.put('routing.json', json.dumps(policy))
        self.config['routing'].pop('requiredTiers')
        self.config['routing']['capability'] = {'model': 'jev-latest', 'minConfidence': 0.8, 'timeoutSeconds': 30}

    def escalation_input(self):
        self.routing_input()
        registry = controller.regression.read_json(self.project/'models.json')
        registry['models']['focused']['reasoningEfforts'].append('xhigh')
        self.put('models.json', json.dumps(registry))
        policy = controller.regression.read_json(self.project/'routing.json')
        policy['tiers']['TIER_2']['fallbacks'] = [
            {'modelId': 'focused', 'reasoningEffort': 'high'}, {'modelId': 'focused', 'reasoningEffort': 'xhigh'}]
        self.put('routing.json', json.dumps(policy))
        self.config['routing']['maxAttempts'] = 3

    def judged(self, tier='TIER_2', status='COMPLETED'):
        result = {'version': 1, 'status': status, 'modelSnapshot': 'jev-1.13.0', 'requestSha256': 'a'*64}
        if status == 'COMPLETED': result.update(requiredTier=tier, risk='LOW', confidence=0.95)
        else: result['failureKind'] = 'LOW_CONFIDENCE'
        return result

    def test_jev_judgment_selects_route_without_receiving_registry_or_credentials(self):
        # given
        self.automatic_input()
        self.run_stage('baseline')
        with patch.object(controller.jev, 'judge', return_value=self.judged()) as judge, \
                patch.object(controller.agent, 'run', side_effect=lambda config, *_: self.routed_result(config)) as run, \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            # when
            result = self.run_stage('generate-tests')
        # then
        self.assertEqual('COMPLETED', result['status'])
        judgment_state = judge.call_args.args[1]
        self.assertEqual('test-writing', judgment_state['stage'])
        self.assertIn('REQ-1', judgment_state['requirements']['requirements'])
        self.assertNotIn('focused-model', json.dumps(judgment_state))
        self.assertNotIn('TYPESAFE_API_KEY', json.dumps(judgment_state))
        self.assertEqual('focused-model', run.call_args.args[0]['model'])
        artifact = controller.regression.read_json(self.state.parent/'agent-test-writing.json')
        self.assertEqual('jev-1.13.0', artifact['capability']['modelSnapshot'])
        self.assertEqual('TIER_2', artifact['routing']['requiredTier'])
        self.assertIn('jev_capability.py', artifact['inputHashes']['agentTools'])

    def test_uncertain_or_unavailable_jev_stops_before_generator_and_writer(self):
        # given
        self.automatic_input()
        self.run_stage('baseline')
        with patch.object(controller.jev, 'judge', return_value=self.judged(status='NEEDS_REVIEW')), \
                patch.object(controller.agent, 'run') as run, patch.object(controller.Controller, '_write') as write:
            # when
            result = self.run_stage('generate-tests')
        # then
        self.assertEqual('NEEDS_REVIEW', result['status'])
        run.assert_not_called()
        write.assert_not_called()
        self.assertEqual('STALE', controller.regression.read_json(self.state)['phase'])

    def test_jev_input_drift_stops_before_any_model_execution(self):
        # given
        self.automatic_input()
        self.run_stage('baseline')
        def judgment(*_):
            self.put('docs/spec.md', 'changed during JEV evaluation')
            return self.judged()
        with patch.object(controller.jev, 'judge', side_effect=judgment), \
                patch.object(controller.agent, 'run') as run, self.assertRaises(ValueError):
            # when
            self.run_stage('generate-tests')
        # then
        run.assert_not_called()
        self.assertEqual('INPUTS', controller.regression.read_json(self.state)['staleKind'])

    def test_automatic_routing_requires_all_tiers_before_baseline(self):
        # given
        self.automatic_input()
        policy = controller.regression.read_json(self.project/'routing.json')
        del policy['tiers']['TIER_4']
        self.put('routing.json', json.dumps(policy))
        # when / then
        with patch.object(controller.regression, 'snapshot') as run, self.assertRaises(ValueError):
            self.run_stage('baseline')
        run.assert_not_called()

    def test_contract_failure_escalates_with_feedback_and_records_every_attempt(self):
        # given
        self.escalation_input()
        self.run_stage('baseline')
        prompts = []
        efforts = []
        workspaces = []
        def generate(config, workspace, prompt):
            efforts.append(config['reasoningEffort'])
            prompts.append(prompt)
            workspaces.append(workspace)
            return {'status': 'INVALID_OUTPUT', 'failureKind': 'OUTPUT_CONTRACT'} if len(efforts) == 1 else self.routed_result(config)
        with patch.object(controller.agent, 'run', side_effect=generate), \
                patch.object(controller.Controller, '_write', side_effect=self.writer) as write:
            # when
            result = self.run_stage('generate-tests')
        # then
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual(['medium', 'high'], efforts)
        self.assertNotEqual(workspaces[0], workspaces[1])
        self.assertIn('OUTPUT_CONTRACT', prompts[1])
        write.assert_called_once()
        artifact = controller.regression.read_json(self.state.parent/'agent-test-writing.json')
        self.assertEqual(2, len(artifact['attempts']))
        self.assertEqual('INVALID_OUTPUT', artifact['attempts'][0]['status'])
        self.assertEqual('high', artifact['routing']['reasoningEffort'])
        self.assertEqual('class NewTest {}', (self.project/'src/test/NewTest.java').read_text())

    def test_escalation_stops_at_xhigh_and_does_not_apply_rejected_changes(self):
        # given
        self.escalation_input()
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run', return_value={'status': 'INVALID_OUTPUT', 'failureKind': 'OUTPUT_CONTRACT'}) as run, \
                patch.object(controller.Controller, '_write') as write:
            # when
            result = self.run_stage('generate-tests')
        # then
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertEqual(['medium', 'high', 'xhigh'], [call.args[0]['reasoningEffort'] for call in run.call_args_list])
        write.assert_not_called()
        self.assertFalse((self.project/'src/test/NewTest.java').exists())
        self.assertEqual(3, len(controller.regression.read_json(self.state.parent/'agent-test-writing.json')['attempts']))

    def test_provider_environment_ambiguity_and_timeout_never_escalate(self):
        # given
        self.escalation_input()
        self.run_stage('baseline')
        for status, failure in (('FAILED', 'INVOCATION'), ('UNAVAILABLE', 'BILLING'), ('TIMED_OUT', 'TIMEOUT'),
                                ('INVALID_OUTPUT', 'PROVIDER_FAILURE'), ('INVALID_OUTPUT', 'OUTPUT_LIMIT'),
                                ('NEEDS_REVIEW', 'AMBIGUITY'), ('INVALID_OUTPUT', None)):
            with self.subTest(status=status, failure=failure):
                response = {'status': status}
                if failure is not None: response['failureKind'] = failure
                with patch.object(controller.agent, 'run', return_value=response) as run, \
                        patch.object(controller.Controller, '_write') as write:
                    # when
                    self.assertEqual('NEEDS_REVIEW', self.run_stage('generate-tests')['status'])
                # then
                run.assert_called_once()
                write.assert_not_called()

    def test_incomplete_test_contract_retries_but_out_of_scope_output_stops(self):
        # given
        self.escalation_input()
        self.run_stage('baseline')
        incomplete = self.generated()
        incomplete['output']['requirementTestMap'] = []
        with patch.object(controller.agent, 'run', side_effect=[incomplete, self.generated()]) as run, \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            # when
            self.assertEqual('COMPLETED', self.run_stage('generate-tests')['status'])
        # then
        self.assertEqual(2, run.call_count)
        with patch.object(controller.agent, 'run', return_value=self.generated('src/main/Service.java')) as run, \
                patch.object(controller.Controller, '_write') as write, self.assertRaises(ValueError):
            self.run_stage('generate-tests')
        run.assert_called_once()
        write.assert_not_called()

    def test_drift_during_failed_attempt_blocks_fallback(self):
        # given
        self.escalation_input()
        self.run_stage('baseline')
        def generation(*_):
            self.put('src/main/Service.java', 'user edit during first attempt')
            return {'status': 'INVALID_OUTPUT', 'failureKind': 'OUTPUT_CONTRACT'}
        with patch.object(controller.agent, 'run', side_effect=generation) as run, \
                patch.object(controller.Controller, '_write') as write, self.assertRaises(ValueError):
            # when
            self.run_stage('generate-tests')
        # then
        run.assert_called_once()
        write.assert_not_called()
        self.assertEqual('INPUTS', controller.regression.read_json(self.state)['staleKind'])

    def test_routing_selects_each_stage_and_binds_decision_to_input_hashes(self):
        # given
        self.routing_input()
        self.run_stage('baseline')
        invocations = []
        def generate(config, workspace, prompt):
            invocations.append(config)
            self.assertFalse((workspace/'models.json').exists())
            self.assertFalse((workspace/'routing.json').exists())
            return self.routed_result(config, len(invocations) == 2)
        # when
        with patch.object(controller.agent, 'run', side_effect=generate), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.assertEqual('COMPLETED', self.run_stage('generate-tests')['status'])
            self.assertEqual('COMPLETED', self.run_stage('generate-implementation')['status'])
        # then
        self.assertEqual(['codex', 'claude'], [config['provider'] for config in invocations])
        self.assertEqual(['focused-model', 'general-model'], [config['model'] for config in invocations])
        self.assertEqual(['medium', 'high'], [config['reasoningEffort'] for config in invocations])
        for stage, tier in (('test-writing', 'TIER_2'), ('implementation', 'TIER_3')):
            record = controller.regression.read_json(self.state.parent/('agent-' + stage + '.json'))
            self.assertEqual(tier, record['routing']['requiredTier'])
            self.assertEqual(controller.regression.digest(self.project/'models.json'), record['routing']['registrySha256'])
            self.assertEqual(controller.regression.digest(self.project/'routing.json'), record['routing']['policySha256'])
            self.assertEqual(record['model'], record['routing']['model'])
            self.assertEqual(record['reasoningEffort'], record['routing']['reasoningEffort'])

    def test_routing_and_direct_provider_cannot_be_combined(self):
        # given
        self.routing_input()
        self.config['agent'] = {'provider': 'codex'}
        # when / then
        with patch.object(controller.regression, 'snapshot') as build, patch.object(controller.agent, 'run') as run:
            with self.assertRaises(ValueError):
                self.run_stage('baseline')
            build.assert_not_called()
            run.assert_not_called()

    def test_invalid_or_inactive_routing_is_rejected_before_baseline_execution(self):
        # given
        self.routing_input()
        original = (self.project/'routing.json').read_text()
        self.put('routing.json', original.replace('"high"', '"max"'))
        # when / then
        with patch.object(controller.regression, 'snapshot') as build, patch.object(controller.agent, 'run') as run:
            with self.assertRaises(ValueError):
                self.run_stage('baseline')
            self.put('routing.json', original)
            self.put('models.json', (self.project/'models.json').read_text().replace('"ACTIVE"', '"SHADOW"'))
            with self.assertRaises(ValueError):
                self.run_stage('baseline')
            build.assert_not_called()
            run.assert_not_called()
        self.assertFalse(self.state.exists())

    def test_registry_or_policy_change_invalidates_session_without_agent_execution(self):
        self.routing_input()
        for name in ('models.json', 'routing.json'):
            with self.subTest(name=name):
                # given
                self.state = self.project/('build/' + name + '/state.json')
                self.run_stage('baseline')
                original = (self.project/name).read_text()
                changed = original.replace('focused-model', 'replacement-model') if name == 'models.json' else original.replace('"medium"', '"high"')
                self.put(name, changed)
                # when / then
                with patch.object(controller.agent, 'run') as run, self.assertRaises(ValueError):
                    self.run_stage('generate-tests')
                run.assert_not_called()
                self.assertEqual('INPUTS', controller.regression.read_json(self.state)['staleKind'])
                self.put(name, original)

    def test_policy_change_during_generation_blocks_application(self):
        # given
        self.routing_input()
        self.run_stage('baseline')
        def generate(config, workspace, prompt):
            self.put('routing.json', (self.project/'routing.json').read_text().replace('"medium"', '"high"'))
            return self.routed_result(config)
        # when / then
        with patch.object(controller.agent, 'run', side_effect=generate), patch.object(controller.Controller, '_write') as write:
            with self.assertRaises(ValueError):
                self.run_stage('generate-tests')
            write.assert_not_called()
        self.assertFalse((self.project/'src/test/NewTest.java').exists())
        self.assertEqual('INPUTS', controller.regression.read_json(self.state)['staleKind'])

    def test_deleted_policy_during_generation_invalidates_session_without_applying(self):
        # given
        self.routing_input()
        self.run_stage('baseline')
        def generate(config, workspace, prompt):
            (self.project/'routing.json').unlink()
            return self.routed_result(config)
        # when / then
        with patch.object(controller.agent, 'run', side_effect=generate), patch.object(controller.Controller, '_write') as write:
            with self.assertRaises(ValueError):
                self.run_stage('generate-tests')
            write.assert_not_called()
        self.assertEqual('STALE', controller.regression.read_json(self.state)['phase'])
        self.assertEqual('INPUTS', controller.regression.read_json(self.state)['staleKind'])
        self.assertFalse((self.project/'src/test/NewTest.java').exists())

    def test_duplicate_keys_and_nonfinite_routing_json_are_rejected_before_execution(self):
        # given
        self.routing_input()
        original = (self.project/'models.json').read_text()
        for text in (original.replace('"version": 1', '"version": 1, "version": 1'),
                     original.replace('"version": 1', '"version": NaN')):
            with self.subTest(text=text):
                self.put('models.json', text)
                # when / then
                with patch.object(controller.regression, 'snapshot') as build, self.assertRaises(ValueError):
                    self.run_stage('baseline')
                build.assert_not_called()
                self.assertFalse(self.state.exists())

    def test_loaded_route_cannot_be_used_with_different_file_hashes(self):
        # given
        self.routing_input()
        self.run_stage('baseline')
        original = (self.project/'routing.json').read_text()
        self.put('routing.json', original.replace('"medium"', '"high"'))
        loaded = controller.Controller(self.config, self.state)
        self.put('routing.json', original)
        # when / then
        with patch.object(controller.agent, 'run') as run, self.assertRaises(ValueError):
            loaded.execute('generate-tests')
        run.assert_not_called()
        self.assertFalse((self.project/'src/test/NewTest.java').exists())

    def test_route_decision_tampering_cannot_unlock_implementation(self):
        # given
        self.routing_input()
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run', side_effect=lambda config, *_: self.routed_result(config)), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.run_stage('generate-tests')
        path = self.state.parent/'agent-test-writing.json'
        record = controller.regression.read_json(path)
        record['routing']['requiredTier'] = 'TIER_1'
        self.put(path.relative_to(self.project).as_posix(), json.dumps(record))
        # when / then
        with patch.object(controller.agent, 'run') as run, self.assertRaises(ValueError):
            self.run_stage('generate-implementation')
        run.assert_not_called()

    def test_routing_change_revokes_final_completion(self):
        # given
        self.routing_input()
        self.implementation()
        self.assertEqual('PASSED', self.verify()['status'])
        self.assertEqual('COMPLETED', self.review()['status'])
        self.assertEqual('COMPLETED', self.run_stage('complete')['status'])
        self.put('models.json', (self.project/'models.json').read_text().replace('focused-model', 'replacement-model'))
        # when / then
        with self.assertRaises(ValueError):
            self.run_stage('complete')
        self.assertEqual('INPUTS', controller.regression.read_json(self.state)['staleKind'])
        self.assertNotIn('completion', controller.regression.read_json(self.state))

    def test_agent_output_uses_existing_writer_and_independent_contexts(self):
        self.agent_input()
        self.run_stage('baseline')
        prompts = []
        workspaces = []
        def generate(config, workspace, prompt):
            workspaces.append(workspace)
            self.assertNotEqual(self.project, workspace)
            self.assertTrue((workspace/'src/main/Service.java').exists())
            self.assertFalse((workspace/'scope.json').exists())
            prompts.append(prompt)
            if len(prompts) == 1:
                self.assertFalse((workspace/'src/test/NewTest.java').exists())
                return self.generated()
            self.assertTrue((workspace/'src/test/NewTest.java').exists())
            result = self.generated('src/main/Service.java')
            result['output'].update(requirementTestMap=[], expectedFailures=[])
            result['output']['changes'][0]['content'] = 'class Service { int value() { return 1; } }'
            return result
        with patch.object(controller.agent, 'run', side_effect=generate), \
                patch.object(controller.Controller, '_write', side_effect=self.writer) as write:
            self.assertEqual('COMPLETED', self.run_stage('generate-tests')['status'])
            self.assertEqual('WRITE_TESTS', write.call_args.args[0])
            self.assertEqual('ABSENT', write.call_args.args[1]['changes'][0]['expectedSha256'])
            self.assertEqual('COMPLETED', self.run_stage('generate-implementation')['status'])
            self.assertEqual('IMPLEMENT', write.call_args.args[0])
        self.assertEqual('class NewTest {}', (self.project/'src/test/NewTest.java').read_text())
        self.assertIn('int value()', (self.project/'src/main/Service.java').read_text())
        self.assertNotEqual(workspaces[0], workspaces[1])
        self.assertTrue(all(not workspace.exists() for workspace in workspaces))
        self.assertIn('"acceptedTests": null', prompts[0])
        self.assertIn('NewTest#boundary', prompts[1])
        state = controller.regression.read_json(self.state)
        self.assertEqual(2, len(state['agentArtifacts']))
        contract = controller.regression.read_json(self.state.parent/'agent-test-writing.json')
        self.assertEqual('SCN-1', contract['output']['requirementTestMap'][0]['scenarioId'])
        self.assertEqual(64, len(contract['promptSha256']))
        with patch.object(controller.agent, 'run') as run:
            with self.assertRaises(ValueError): self.run_stage('generate-tests')
            run.assert_not_called()

    def test_agent_cannot_write_outside_scope_or_incomplete_requirement_map(self):
        self.agent_input()
        self.run_stage('baseline')
        results = [self.generated('src/main/Service.java'), self.generated('src/test/Unapproved.java'), self.generated()]
        results[-1]['output']['requirementTestMap'] = []
        with patch.object(controller.Controller, '_write') as write:
            for result in results:
                with patch.object(controller.agent, 'run', return_value=result), self.assertRaises(ValueError):
                    self.run_stage('generate-tests')
            write.assert_not_called()
        self.assertFalse((self.project/'src/test/NewTest.java').exists())
        self.assertEqual('class Service {}', (self.project/'src/main/Service.java').read_text())

    def test_agent_failure_is_review_pending_and_can_retry_same_stage(self):
        self.agent_input()
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run', return_value={'status': 'TIMED_OUT'}), \
                patch.object(controller.Controller, '_write') as write:
            self.assertEqual('NEEDS_REVIEW', self.run_stage('generate-tests')['status'])
            write.assert_not_called()
            with self.assertRaises(ValueError): self.run_stage('generate-implementation')
        with patch.object(controller.agent, 'run', return_value=self.generated()), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.assertEqual('COMPLETED', self.run_stage('generate-tests')['status'])
        with patch.object(controller.agent, 'run', return_value={'status': 'UNAVAILABLE'}):
            self.assertEqual('NEEDS_REVIEW', self.run_stage('generate-implementation')['status'])
        result = self.generated('src/main/Service.java')
        result['output'].update(requirementTestMap=[], expectedFailures=[])
        with patch.object(controller.agent, 'run', return_value=result), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.assertEqual('COMPLETED', self.run_stage('generate-implementation')['status'])

    def test_agent_source_drift_and_cancellation_block_application(self):
        self.agent_input()
        self.run_stage('baseline')
        def drift(*args):
            self.put('src/main/Service.java', 'user edit')
            return self.generated()
        with patch.object(controller.agent, 'run', side_effect=drift), patch.object(controller.Controller, '_write') as write:
            with self.assertRaises(ValueError): self.run_stage('generate-tests')
            write.assert_not_called()
        self.assertEqual('user edit', (self.project/'src/main/Service.java').read_text())
        self.assertEqual('INPUTS', controller.regression.read_json(self.state)['staleKind'])

    def test_agent_contract_and_rules_drift_rejects_reuse(self):
        self.agent_input()
        self.put('AGENTS.md', 'Preserve observable behavior')
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run', return_value=self.generated()), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.run_stage('generate-tests')
        self.put('build/session/agent-test-writing.json', '{}')
        with patch.object(controller.agent, 'run') as run, self.assertRaises(ValueError):
            self.run_stage('generate-implementation')
        run.assert_not_called()

    def test_agent_ambiguity_stops_before_provider_and_rule_changes_invalidate_session(self):
        self.agent_input()
        req = controller.regression.read_json(self.project/'requirements.json')
        req['requirements']['REQ-1']['review']['openQuestions'] = ['Unspecified boundary']
        self.put('requirements.json', json.dumps(req))
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run') as run, self.assertRaises(ValueError):
            self.run_stage('generate-tests')
        run.assert_not_called()
        self.put('AGENTS.md', 'New policy')
        with patch.object(controller.agent, 'run') as run, self.assertRaises(ValueError):
            self.run_stage('generate-tests')
        run.assert_not_called()
        self.assertEqual('INPUTS', controller.regression.read_json(self.state)['staleKind'])

    def test_missing_generated_test_and_modified_contract_cannot_finish(self):
        self.agent_input()
        self.run_stage('baseline')
        result = self.generated()
        with patch.object(controller.agent, 'run', return_value=result), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.run_stage('generate-tests')
        result = self.generated('src/main/Service.java')
        result['output'].update(changes=[], requirementTestMap=[], expectedFailures=[])
        with patch.object(controller.agent, 'run', return_value=result):
            self.assertEqual('COMPLETED', self.run_stage('generate-implementation')['status'])
        with patch.object(controller.Controller, '_scan') as scan:
            verification = self.run_stage('verification')
            self.assertEqual('FAILED', verification['status'])
            self.assertIn('Generated requirement test missing or failed: NewTest#boundary', verification['failures'])
            scan.assert_not_called()
        self.put('build/session/agent-test-writing.json', '{}')
        with patch.object(controller.regression, 'snapshot') as build, self.assertRaises(ValueError):
            self.run_stage('verification')
        build.assert_not_called()

    def test_agent_interrupt_revokes_acceptance_and_blocks_later_stages(self):
        self.agent_input()
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run', side_effect=KeyboardInterrupt), \
                patch.object(controller.Controller, '_write') as write:
            with self.assertRaises(KeyboardInterrupt): self.run_stage('generate-tests')
            write.assert_not_called()
        state = controller.regression.read_json(self.state)
        self.assertEqual('STALE', state['phase'])
        self.assertNotIn('verified', state)
        with self.assertRaises(ValueError): self.run_stage('generate-implementation')
        with self.assertRaises(ValueError): self.run_stage('complete')

    def test_generated_display_name_requires_exact_passing_junit_case(self):
        self.agent_input()
        self.run_stage('baseline')
        result = self.generated()
        identifier = 'NewTest#하한 경계인 0을 허용한다'
        result['output']['requirementTestMap'][0]['tests'] = [identifier]
        result['output']['expectedFailures'][0]['test'] = identifier
        with patch.object(controller.agent, 'run', return_value=result), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.run_stage('generate-tests')
        result = self.generated('src/main/Service.java')
        result['output'].update(changes=[], requirementTestMap=[], expectedFailures=[])
        with patch.object(controller.agent, 'run', return_value=result):
            self.run_stage('generate-implementation')
        snapshot = controller.regression.snapshot(self.project, controller.regression.read_json(self.project/'plan.json'))
        snapshot['tests'][identifier] = 'PASSED'
        def scan(): self.put('build/session/scan.json', '{"findings":[]}')
        with patch.object(controller.regression, 'snapshot', return_value=snapshot), \
                patch.object(controller.Controller, '_scan', side_effect=scan):
            self.assertEqual('PASSED', self.run_stage('verification')['status'])
        snapshot['tests'][identifier] = 'SKIPPED'
        with patch.object(controller.regression, 'snapshot', return_value=snapshot):
            self.assertEqual('FAILED', self.run_stage('verification')['status'])

    def test_generated_tests_may_not_use_unapproved_project_types_or_undeclared_libraries(self):
        # given
        self.put('src/main/java/com/acme/App.java', 'package com.acme;\n@SpringBootApplication\nclass App {}\n')
        self.agent_input()
        self.run_stage('baseline')
        result = self.generated()
        result['output']['changes'][0]['content'] = ('package com.acme;\nimport com.acme.App;\nimport com.acme.member.PasswordPort;\n'
                                                     'import static com.acme.member.Clocks.FIXED;\n'
                                                     'import org.springframework.security.crypto.bcrypt.BCrypt;\nclass NewTest {}\n')
        # when
        with patch.object(controller.agent, 'run', return_value=result), \
                patch.object(controller.Controller, '_write') as write, self.assertRaises(ValueError):
            self.run_stage('generate-tests')
        # then
        write.assert_not_called()
        detail = controller.regression.read_json(self.project/'build/session/agent-test-writing.json')['detail']
        self.assertIn('com.acme.member.Clocks, com.acme.member.PasswordPort', detail)
        self.assertNotIn('com.acme.App', detail)
        self.assertIn('org.springframework.security', detail)

    def test_test_writer_sees_the_approved_production_files(self):
        self.agent_input()
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run', return_value=self.generated()) as run, \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.run_stage('generate-tests')
        task = json.loads(run.call_args[0][2].rsplit('\n', 1)[1])
        scope = controller.regression.read_json(self.project/'scope.json')['files']
        self.assertEqual([path for path in scope if path.startswith('src/main/')], task['approvedProductionFiles'])
        self.assertTrue(task['approvedProductionFiles'])

    def test_unfinished_implementation_call_can_be_retried_in_the_same_session(self):
        # given: tests accepted, then the implementation model stops with a question
        self.agent_input()
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run', return_value=self.generated()), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.run_stage('generate-tests')
        question = {'version': 1, 'status': 'NEEDS_REVIEW', 'failureKind': 'AMBIGUITY'}
        with patch.object(controller.agent, 'run', return_value=question):
            self.assertEqual('NEEDS_REVIEW', self.run_stage('generate-implementation')['status'])
        state = controller.regression.read_json(self.state)
        self.assertEqual(('STALE', 'implementation'), (state['phase'], state['generationStage']))
        # when
        result = self.generated('src/main/Service.java')
        result['output'].update(changes=[], requirementTestMap=[], expectedFailures=[])
        with patch.object(controller.agent, 'run', return_value=result):
            retried = self.run_stage('generate-implementation')
        # then
        self.assertEqual('COMPLETED', retried['status'])
        self.assertEqual('IMPLEMENTATION', controller.regression.read_json(self.state)['phase'])

    def test_generated_tests_calling_a_missing_library_member_are_rejected_before_writing(self):
        # given: compiling the proposed tests reports a missing project class (expected) and a missing AssertJ method
        self.agent_input()
        self.put('gradlew', '''#!/bin/sh
case "$*" in *compileTestJava*)
  test -f src/test/NewTest.java || exit 0
  echo "$PWD/src/test/NewTest.java:3: error: cannot find symbol"
  echo "  symbol:   class Service"
  echo "  location: class NewTest"
  echo "$PWD/src/test/NewTest.java:9: error: cannot find symbol"
  echo "  symbol:   method isCompatibleWith(MediaType)"
  echo "  location: class ObjectAssert<MediaType>"
  exit 1;;
esac
''' + (self.project/'gradlew').read_text().split('\n', 1)[1])
        self.run_stage('baseline')
        # when
        with patch.object(controller.agent, 'run', return_value=self.generated()), \
                patch.object(controller.Controller, '_write') as write, self.assertRaises(ValueError):
            self.run_stage('generate-tests')
        # then
        write.assert_not_called()
        detail = controller.regression.read_json(self.project/'build/session/agent-test-writing.json')['detail']
        self.assertIn('NewTest.java:9 method isCompatibleWith(MediaType) is not a member of ObjectAssert', detail)
        self.assertNotIn('Service', detail)

    def test_test_writing_rejects_production_change_before_writer(self):
        self.run_stage('baseline')
        with patch.object(controller.Controller, '_write') as write:
            with self.assertRaises(ValueError):
                self.run_stage('test-writing', changes=self.changes('src/main/Service.java'))
            write.assert_not_called()
        self.assertEqual('class Service {}', (self.project/'src/main/Service.java').read_text())

    def test_failed_baseline_cannot_unlock_writes(self):
        self.put('gradlew', '#!/bin/sh\nexit 1\n')
        result = self.run_stage('baseline')
        self.assertEqual('FAILED', result['status'])
        with self.assertRaises(ValueError): self.run_stage('test-writing', changes=self.changes('src/test/ServiceTest.java'))
        with self.assertRaises(ValueError): self.run_stage('baseline')

    def test_general_project_full_flow_without_fixture_policy(self):
        self.implementation()
        self.assertEqual('PASSED', self.verify()['status'])
        self.assertEqual('COMPLETED', self.review()['status'])
        result = self.run_stage('complete')
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual('NO_REPORTED_RISKS', result['targetRiskStatus'])
        self.assertEqual('REVIEWED', controller.regression.read_json(self.state)['history'][-2]['phase'])

    def test_regression_skip_and_unresolved_risk_block_later_phases(self):
        self.implementation()
        self.put('gradlew', (self.project/'gradlew').read_text().replace('name="works()"/>', 'name="works()"><skipped/></testcase>'))
        with self.assertRaises(ValueError): self.verify()
        # A changed build input must be accepted again through test-writing before it can be verified.
        with self.assertRaisesRegex(ValueError, 'test-writing'): self.verify()
        with patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.run_stage('test-writing', changes=self.changes('src/test/ServiceTest.java'))
            self.run_stage('implementation', changes=self.changes('src/main/Service.java'))
        self.assertEqual('FAILED', self.verify()['status'])
        with self.assertRaises(ValueError): self.run_stage('review', bundle={}, service={}, open_evidence=[])
        with self.assertRaises(ValueError): self.run_stage('complete')

    def test_general_project_risk_never_gets_fixture_exception(self):
        self.implementation()
        self.scan_report = {'findings': [{'ruleId': 'TX-001', 'status': 'CONFIRMED',
            'severity': 'HIGH', 'location': {'file': 'src/main/Service.java', 'startLine': 1}}]}
        self.verify()
        result = self.review()
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertEqual('UNRESOLVED', result['targetRiskStatus'])
        with self.assertRaises(ValueError): self.run_stage('complete')

    def test_replaced_static_report_is_invalidated(self):
        self.implementation(); self.verify()
        self.put('build/session/scan.json', json.dumps({'findings': [], 'changed': True}))
        with self.assertRaises(ValueError): self.review()

    def test_source_test_and_requirement_drift_invalidate_review(self):
        for path in ('src/main/Service.java', 'src/test/ServiceTest.java', 'docs/spec.md'):
            with self.subTest(path=path):
                # Each independent session obtains fresh execution and review evidence.
                if self.state.exists():
                    import shutil
                    shutil.rmtree(self.state.parent)
                self.implementation(); self.verify(); self.review()
                original = (self.project/path).read_text()
                self.put(path, original + '\nchanged')
                with self.assertRaises(ValueError): self.run_stage('complete')
                state = controller.regression.read_json(self.state)
                self.assertEqual('STALE', state['phase'])
                self.assertNotIn('completion', state)
                self.assertTrue(state['invalidations'])
                self.put(path, original)

    def test_changed_junit_and_service_artifacts_cannot_finish(self):
        self.implementation(); self.verify(); self.review()
        self.put('build/libs/service.jar', 'different artifact')
        with self.assertRaises(ValueError): self.run_stage('complete')
        self.assertEqual('STALE', controller.regression.read_json(self.state)['phase'])

    def test_failed_writer_cannot_unlock_completion(self):
        self.run_stage('baseline')
        with patch.object(controller.Controller, '_write', return_value={'status': 'FAILED'}):
            self.run_stage('test-writing', changes=self.changes('src/test/ServiceTest.java'))
        with self.assertRaises(ValueError): self.run_stage('implementation', changes=self.changes('src/main/Service.java'))
        with self.assertRaises(ValueError): self.run_stage('verification')

    def test_fresh_verification_requires_new_review_after_artifact_drift(self):
        self.implementation(); self.verify(); self.review()
        self.put('build/libs/service.jar', 'different artifact')
        with self.assertRaises(ValueError): self.run_stage('complete')
        self.assertEqual('PASSED', self.verify()['status'])
        with self.assertRaises(ValueError): self.run_stage('complete')
        self.review()
        self.assertEqual('COMPLETED', self.run_stage('complete')['status'])

    def test_failed_service_revokes_previous_review_acceptance(self):
        self.implementation(); self.verify(); self.review()
        with self.assertRaisesRegex(ValueError, 'local-service'): self.review(service_status='FAILED')
        with self.assertRaises(ValueError): self.run_stage('complete')

    def test_caller_service_evidence_without_adapter_never_completes(self):
        self.config.pop('serviceAdapter')
        self.implementation()
        self.assertEqual('PASSED', self.verify()['status'])
        snap = controller.regression.read_json(self.state.parent/'after.json')
        report = self.state.parent/'scan.json'
        bundle = {'version': 1, 'snapshotSha256': controller.evidence.fingerprint(snap),
                  'reportSha256': controller.regression.digest(report), 'records': []}
        forged = {'status': 'PASSED', 'sources': snap['sources'], 'jarSha256': snap['artifactSha256']['service.jar'],
                  'scenarios': dict.fromkeys(['normal', 'duplicate', 'concurrency', 'rollback'], 'PASSED'), 'processStopped': True}
        result = self.run_stage('review', bundle=bundle, service=forged, open_evidence=[])
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertIn('Service evidence was supplied by the caller, not executed by a controller adapter', result['openEvidence'])
        with self.assertRaises(ValueError): self.run_stage('complete')
        with self.assertRaises(ValueError): self.run_stage('pull-request', pull_request={'title': 'change'})

    def test_caller_service_evidence_cannot_replace_controller_execution(self):
        self.implementation(); self.verify(); self.review()
        snap = controller.regression.read_json(self.state.parent/'after.json')
        forged = {'status': 'PASSED', 'sources': snap['sources'], 'jarSha256': snap['artifactSha256']['service.jar'],
                  'scenarios': dict.fromkeys(['normal', 'duplicate', 'concurrency', 'rollback'], 'PASSED'), 'processStopped': True}
        with self.assertRaisesRegex(ValueError, 'External service'):
            self.run_stage('review', bundle={}, service=forged, open_evidence=[])

    def test_implementation_cannot_change_tests(self):
        self.run_stage('baseline')
        with patch.object(controller.Controller, '_write', side_effect=self.writer) as write:
            self.run_stage('test-writing', changes=self.changes('src/test/ServiceTest.java'))
            with self.assertRaisesRegex(ValueError, 'src/main'):
                self.run_stage('implementation', changes=self.changes('src/test/ServiceTest.java'))
            self.assertEqual(1, write.call_count)

    def test_tests_changed_after_test_writing_block_verification(self):
        self.implementation()
        self.put('src/test/ServiceTest.java', 'class ServiceTest { /* assertion removed */ }')
        with self.assertRaises(ValueError): self.verify()
        with patch.object(controller.regression, 'snapshot') as build:
            with self.assertRaisesRegex(ValueError, 'test-writing'): self.verify()
            build.assert_not_called()
        state = controller.regression.read_json(self.state)
        self.assertEqual('STALE', state['phase'])
        self.assertNotIn('verified', state)
        with patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.assertEqual('COMPLETED', self.run_stage('test-writing', changes=self.changes('src/test/ServiceTest.java'))['status'])
            self.run_stage('implementation', changes=self.changes('src/main/Service.java'))
        self.assertEqual('PASSED', self.verify()['status'])

    def new_feature(self, *tests):
        plan = controller.regression.read_json(self.project/'plan.json')
        plan['newFeatures'] = {'REQ-1': {'domain': list(tests), 'mysql': [], 'mysqlRequired': False}}
        self.put('plan.json', json.dumps(plan))

    def test_new_feature_test_passing_before_implementation_blocks_implementation(self):
        self.new_feature('ServiceTest#works')
        self.run_stage('baseline')
        with patch.object(controller.Controller, '_write', side_effect=self.writer):
            result = self.run_stage('test-writing', changes=self.changes('src/test/ServiceTest.java'))
            self.assertEqual('NEEDS_REVIEW', result['status'])
            self.assertIn('Passed before implementation: ServiceTest#works', result['failures'])
            with self.assertRaises(ValueError):
                self.run_stage('implementation', changes=self.changes('src/main/Service.java'))
        red = controller.regression.read_json(self.state.parent/'red.json')
        self.assertEqual('NEEDS_REVIEW', red['status'])
        self.assertEqual('STALE', controller.regression.read_json(self.state)['phase'])

    def test_new_feature_test_failing_before_implementation_is_recorded(self):
        self.new_feature('ServiceTest#rejectsNegative')
        self.run_stage('baseline')
        with patch.object(controller.Controller, '_write', side_effect=self.writer):
            self.assertEqual('COMPLETED', self.run_stage('test-writing', changes=self.changes('src/test/ServiceTest.java'))['status'])
        red = controller.regression.read_json(self.state.parent/'red.json')
        self.assertEqual('PASSED', red['status'])
        self.assertEqual({'ServiceTest#rejectsNegative': 'NOT_PASSED'}, red['tests'])
        state = controller.regression.read_json(self.state)
        self.assertEqual(controller.regression.digest(self.state.parent/'red.json'), state['redSha256'])

    def test_generated_expected_failure_that_already_passes_is_rejected(self):
        self.agent_input()
        self.put('gradlew', (self.project/'gradlew').read_text().replace(
            'name="works()"/>', 'name="works()"/><testcase classname="NewTest" name="boundary()"/>'))
        self.run_stage('baseline')
        with patch.object(controller.agent, 'run', return_value=self.generated()), \
                patch.object(controller.Controller, '_write', side_effect=self.writer):
            result = self.run_stage('generate-tests')
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertIn('Passed before implementation: NewTest#boundary', result['failures'])
        with patch.object(controller.agent, 'run') as run, self.assertRaises(ValueError):
            self.run_stage('generate-implementation')
        run.assert_not_called()

    def test_invalid_new_review_revokes_previous_acceptance(self):
        self.implementation(); self.verify(); self.review()
        with self.assertRaises(ValueError):
            self.run_stage('review', bundle={'invalid': True}, service={}, open_evidence=[])
        with self.assertRaises(ValueError): self.run_stage('complete')

    def test_changed_review_input_artifact_cannot_finish(self):
        self.implementation(); self.verify(); self.review()
        self.put('build/session/review-inputs.json', '{}')
        with self.assertRaises(ValueError): self.run_stage('complete')
        self.assertEqual('STALE', controller.regression.read_json(self.state)['phase'])

    def test_document_modes_reject_without_project_hash_or_build(self):
        for mode in ('SPEC_ONLY', 'SPEC_CODE_REVIEW'):
            self.put('requirements.json', json.dumps({'mode': mode}))
            with patch.object(controller.regression, 'source_hashes') as hashes:
                with self.assertRaises(ValueError): self.run_stage('baseline')
                hashes.assert_not_called()

    def test_writer_requests_machine_readable_response_without_startup_logs(self):
        self.run_stage('baseline')
        runner = controller.Controller(self.config, self.state)
        with patch.object(runner, '_process', return_value='{"status":"COMPLETED"}') as process:
            result = runner._write('WRITE_TESTS', self.changes('src/test/ServiceTest.java'))
        command = process.call_args.args[0]
        self.assertIn('-Dspring.main.banner-mode=off', command)
        self.assertIn('-Dlogging.level.root=OFF', command)
        self.assertEqual('COMPLETED', result['status'])
        self.assertFalse((self.state.parent/'changes.json').exists())

    def test_contract_changes_and_regression_reach_are_reported_without_blocking(self):
        # given: a baseline contract snapshot and an analyzer that reports one removed method
        self.run_stage('baseline')
        runner = controller.Controller(self.config, self.state)
        controller.regression.save(self.state.parent/'contracts-before.json', {'version': 1, 'contracts': {}})
        report = {'contracts': {}, 'changes': [{'change': 'API_REMOVED', 'key': 'METHOD demo.Service#run()', 'effect': 'e',
                                                'before': {}, 'after': None}],
                  'impact': {'callerFiles': ['src/main/Caller.java'], 'tests': {'demo.ServiceTest#works()': 2}}}
        result = {'status': 'PASSED', 'failures': []}
        with patch.object(runner, '_impact', return_value=report) as impact:
            runner._contract_review(result)
        # then
        impact.assert_called_once_with(self.state.parent/'contracts-before.json')
        self.assertEqual('PASSED', result['status'])
        self.assertEqual([{'change': 'API_REMOVED', 'key': 'METHOD demo.Service#run()', 'effect': 'e'}], result['contractChanges'])
        self.assertEqual((['src/main/Caller.java'], ['demo.ServiceTest#works()']),
                         (result['impactedCallerFiles'], result['impactedTests']))
        self.assertTrue((self.state.parent/'impact.json').is_file())

    def test_contract_check_is_unavailable_without_a_real_analyzer_jar(self):
        # given: the test analyzer is not a jar, so baseline wrote no contract snapshot
        self.run_stage('baseline')
        result = {'status': 'PASSED', 'failures': []}
        controller.Controller(self.config, self.state)._contract_review(result)
        self.assertFalse((self.state.parent/'contracts-before.json').exists())
        self.assertEqual(('PASSED', 'UNAVAILABLE'), (result['status'], result['contractCheck']))

    def test_session_path_cannot_escape_build_via_parent_traversal(self):
        escaped = self.project/'build/../src/session/state.json'
        with self.assertRaises(ValueError): controller.Controller(self.config, escaped)
        self.assertFalse((self.project/'src/session').exists())

    def fixture_mode(self):
        self.config.pop('serviceAdapter')
        self.config['fixtureAdapter'] = 'MYSQL'
        project = patch.object(controller, 'FIXTURE_PROJECT', self.project)
        project.start()
        self.addCleanup(project.stop)

    def service_result(self, status='PASSED'):
        snap = controller.regression.read_json(self.state.parent/'after.json')
        return {'status': status, 'sources': snap['sources'], 'jarSha256': snap['artifactSha256']['service.jar'],
                'scenarios': dict.fromkeys(['normal', 'duplicate', 'concurrency', 'rollback'], 'PASSED'), 'processStopped': True}

    def test_fixture_review_requires_controller_service_execution(self):
        self.fixture_mode(); self.implementation(); self.verify()
        with self.assertRaises(ValueError): self.review()
        with patch.object(controller.Controller, '_service', return_value=self.service_result()) as adapter:
            self.assertEqual('PASSED', self.run_stage('local-service', container='owned-container')['status'])
            adapter.assert_called_once_with('owned-container')
        self.assertEqual('COMPLETED', self.review()['status'])
        self.assertEqual('COMPLETED', self.run_stage('complete')['status'])

    def test_service_failure_and_interruption_require_retry_and_new_review(self):
        self.fixture_mode(); self.implementation(); self.verify()
        with patch.object(controller.Controller, '_service', return_value=self.service_result()):
            self.run_stage('local-service', container='owned')
        self.review()
        for outcome in ('FAILED', 'INTERRUPTED'):
            with self.subTest(outcome=outcome):
                if outcome == 'FAILED':
                    with patch.object(controller.Controller, '_service', return_value=self.service_result('FAILED')):
                        self.assertEqual('FAILED', self.run_stage('local-service', container='owned')['status'])
                else:
                    with patch.object(controller.Controller, '_service', side_effect=KeyboardInterrupt):
                        with self.assertRaises(KeyboardInterrupt): self.run_stage('local-service', container='owned')
                with self.assertRaises(ValueError): self.review()
                with self.assertRaises(ValueError): self.run_stage('complete')
                with patch.object(controller.Controller, '_service', return_value=self.service_result()):
                    self.run_stage('local-service', container='owned')
                self.review()
        self.assertEqual('COMPLETED', self.run_stage('complete')['status'])

    def test_source_change_during_service_invalidates_all_acceptance(self):
        self.fixture_mode(); self.implementation(); self.verify()
        def drift(container):
            result = self.service_result()
            self.put('src/main/Service.java', 'changed during service')
            return result
        with patch.object(controller.Controller, '_service', side_effect=drift):
            with self.assertRaises(ValueError): self.run_stage('local-service', container='owned')
        state = controller.regression.read_json(self.state)
        self.assertEqual('STALE', state['phase'])
        self.assertNotIn('serviceSha256', state)
        with self.assertRaises(ValueError): self.run_stage('complete')

    def test_old_service_result_cannot_survive_reverification(self):
        self.fixture_mode(); self.implementation(); self.verify()
        with patch.object(controller.Controller, '_service', return_value=self.service_result()):
            self.run_stage('local-service', container='owned')
        self.verify()
        with self.assertRaises(ValueError): self.review()

    def test_fixture_preset_cannot_run_on_general_project(self):
        self.config['fixtureAdapter'] = 'MYSQL'
        with self.assertRaises(ValueError): self.run_stage('baseline')

    def test_service_refuses_db_url_outside_owned_container(self):
        self.fixture_mode()
        runner = controller.Controller(self.config, self.state)
        responses = [Mock(returncode=0, stdout='spring-risk-sample\n'), Mock(returncode=0, stdout='127.0.0.1:5050\n')]
        with patch.object(controller.subprocess, 'run', side_effect=responses), \
             patch.dict(os.environ, {'RISK_SAMPLE_MYSQL_URL': 'jdbc:mysql://127.0.0.1:9999/risk_sample', 'RISK_SAMPLE_MYSQL_USER': 'risk_sample'}), \
             patch.object(controller, 'fixture_tools') as adapter:
            with self.assertRaises(ValueError): runner._service('code-risk-sample-012345abcdef')
        adapter.assert_not_called()

    def test_service_passes_only_owned_loopback_db_to_adapter(self):
        self.fixture_mode()
        runner = controller.Controller(self.config, self.state)
        responses = [Mock(returncode=0, stdout='spring-risk-sample\n'), Mock(returncode=0, stdout='127.0.0.1:5050\n')]
        tools = Mock()
        tools.verify_service.return_value = {'status': 'PASSED'}
        with patch.object(controller.subprocess, 'run', side_effect=responses), \
             patch.dict(os.environ, {'RISK_SAMPLE_MYSQL_URL': 'jdbc:mysql://127.0.0.1:5050/risk_sample?sslMode=DISABLED', 'RISK_SAMPLE_MYSQL_USER': 'risk_sample'}), \
             patch.object(controller, 'fixture_tools', return_value=tools):
            self.assertEqual('PASSED', runner._service('code-risk-sample-012345abcdef')['status'])
        self.assertEqual(self.state.parent/'service-running.json', tools.verify_service.call_args.args[2])


class LibraryMisuseTests(unittest.TestCase):
    """Real javac output: a missing library member is rejected, a project type that does not exist yet is expected."""

    def test_unknown_library_method_is_reported_and_missing_project_code_is_not(self):
        import shutil
        import subprocess
        if shutil.which('javac') is None:
            self.skipTest('javac not installed')
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'Assertions.java').write_text(
                'class Assertions { static Assertions assertThat(String v) { return new Assertions(); } '
                'Assertions contains(String v) { return this; } }')
            (root/'OrderTest.java').write_text(
                'class OrderTest { void test() {\n'
                '  Assertions.assertThat("x").containsJson("y");\n'  # library type: method does not exist
                '  Order order = Order.create();\n'                  # project type: written in implementation
                '} }')
            result = subprocess.run(['javac', '-d', str(root/'out'), str(root/'Assertions.java'), str(root/'OrderTest.java')],
                                    capture_output=True, text=True)
            output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode)
        errors = controller.library_misuse(output, {'Order', 'OrderTest'})
        self.assertEqual(1, len(errors), output)
        self.assertIn('OrderTest.java:2', errors[0])
        self.assertIn('containsJson', errors[0])
        self.assertIn('Assertions', errors[0])

    def test_no_errors_when_only_project_types_are_missing(self):
        output = ('/p/src/test/java/OrderTest.java:5: error: cannot find symbol\n'
                  '    Order.create();\n    ^\n  symbol:   method create()\n  location: class Order\n')
        self.assertEqual([], controller.library_misuse(output, {'Order'}))


if __name__ == '__main__':
    unittest.main()
