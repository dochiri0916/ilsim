import importlib.util
import json
import os
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'regression_workflow.py'
spec = importlib.util.spec_from_file_location('regression_workflow', MODULE)
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


class RegressionWorkflowTests(unittest.TestCase):
    def baseline(self):
        return {'version': 1, 'project': '/project', 'features': {'price': ['PriceTest#boundary']},
                'sources': {'src/main/Price.java': 'old'},
                'protected': {'src/test/PriceTest.java': 'old'},
                'environment': {'java': '25', 'gradle': '9', 'database': 'MySQL 8.4'},
                'command': ['./gradlew', 'cleanTest', 'build', '--rerun-tasks', '--no-daemon'],
                'tests': {'PriceTest#boundary': 'PASSED'}, 'buildExitCode': 0}

    def compare(self, before, after, **kwargs):
        return workflow.compare(before, after, **kwargs)

    def test_unchanged_contract_passes_but_changed_test_requires_review(self):
        before = self.baseline()
        after = json.loads(json.dumps(before))
        self.assertEqual('PASSED', self.compare(before, after)['status'])
        after['protected']['src/test/PriceTest.java'] = 'weakened'
        self.assertEqual('NEEDS_REVIEW', self.compare(before, after)['status'])

    def test_approval_bound_to_both_hashes_and_explicit_contract_reason(self):
        before = self.baseline()
        after = json.loads(json.dumps(before))
        after['protected']['src/test/PriceTest.java'] = 'new'
        approval = {'path': 'src/test/PriceTest.java', 'beforeSha256': 'old', 'afterSha256': 'new',
                    'basis': 'SPEC_CHANGE', 'reference': 'definition section 2',
                    'reviewer': 'user', 'reason': 'quantity boundary changed'}
        self.assertEqual('PASSED', self.compare(before, after, approvals=[approval])['status'])
        approval['afterSha256'] = 'other'
        self.assertEqual('NEEDS_REVIEW', self.compare(before, after, approvals=[approval])['status'])

    def test_failure_skip_missing_and_preexisting_failure_block_completion(self):
        for state in ('FAILED', 'SKIPPED', None):
            with self.subTest(state=state):
                before = self.baseline()
                after = json.loads(json.dumps(before))
                if state is None:
                    after['tests'].clear()
                else:
                    after['tests']['PriceTest#boundary'] = state
                self.assertEqual('FAILED', self.compare(before, after)['status'])
        before = self.baseline()
        before['tests']['PriceTest#boundary'] = 'FAILED'
        self.assertEqual('FAILED', self.compare(before, self.baseline())['status'])

    def test_environment_or_feature_mapping_change_is_not_comparable(self):
        for field, replacement in [('environment', {'java': '26'}), ('features', {'other': []}),
                                   ('command', ['test']), ('project', '/other')]:
            before = self.baseline()
            after = json.loads(json.dumps(before))
            after[field] = replacement
            self.assertEqual('NEEDS_REVIEW', self.compare(before, after)['status'])

    def test_new_feature_requires_domain_and_requested_mysql_tests(self):
        before = self.baseline()
        after = json.loads(json.dumps(before))
        new = {'create': {'domain': ['Domain#valid'], 'mysql': ['Database#rollback'], 'mysqlRequired': True}}
        self.assertEqual('FAILED', self.compare(before, after, new_features=new)['status'])
        after['tests'].update({'Domain#valid': 'PASSED', 'Database#rollback': 'PASSED'})
        self.assertEqual('PASSED', self.compare(before, after, new_features=new)['status'])
        after['environment']['database'] = 'not verified'
        self.assertNotEqual('PASSED', self.compare(before, after, new_features=new)['status'])

    def test_final_gate_requires_service_current_source_and_no_open_evidence(self):
        snapshot = self.baseline()
        regression = self.compare(snapshot, snapshot)
        service = {'status': 'PASSED', 'sources': snapshot['sources'],
                   'scenarios': {'normal': 'PASSED', 'duplicate': 'PASSED', 'concurrency': 'PASSED', 'rollback': 'PASSED'},
                   'processStopped': True}
        self.assertEqual('COMPLETED', workflow.completion(regression, snapshot, service, [])['status'])
        self.assertEqual('NEEDS_REVIEW', workflow.completion(regression, snapshot, service, ['TX evidence missing'])['status'])
        service['sources'] = {'old': 'hash'}
        self.assertNotEqual('COMPLETED', workflow.completion(regression, snapshot, service, [])['status'])
        service['processStopped'] = False
        self.assertEqual('FAILED', workflow.completion(regression, snapshot, service, [])['status'])

    def test_feature_tests_accept_method_and_display_names_only(self):
        workflow.validate_tests(['com.example.PriceTest#boundary', 'com.example.PriceTest#경계 가격을 거절한다',
                                 'com.example.PriceTest#parameterized(int)[1]'])
        for invalid in (['PriceTest'], ['#boundary'], ['PriceTest# boundary'], ['PriceTest#boundary '],
                        ['PriceTest#a#b'], ['PriceTest#a\nb'], ['PriceTest#a', 'PriceTest#a'], []):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    workflow.validate_tests(invalid)

    def test_spec_only_and_review_are_rejected_before_project_read(self):
        for mode in ('SPEC_ONLY', 'SPEC_CODE_REVIEW'):
            with self.assertRaises(ValueError):
                workflow.validate_plan({'mode': mode, 'project': '/missing', 'approvedActions': []})

    def test_duplicate_json_keys_and_symlink_sources_are_rejected(self):
        with self.assertRaises(ValueError):
            workflow.parse_json('{"mode":"CODE_CHANGE","mode":"SPEC_ONLY"}')
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / 'src/test').mkdir(parents=True)
            (project / 'src/test/escape.java').symlink_to('/etc/hosts')
            with self.assertRaises(ValueError):
                workflow.source_hashes(project)

    def test_junit_skipped_failure_and_duplicate_identifiers_are_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory)
            xml = results / 'TEST-example.xml'
            xml.write_text('<testsuite><testcase classname="PriceTest" name="boundary()"/><testcase classname="PriceTest" name="skip()"><skipped/></testcase><testcase classname="PriceTest" name="fail()"><failure/></testcase></testsuite>')
            self.assertEqual({'PriceTest#boundary': 'PASSED', 'PriceTest#skip': 'SKIPPED', 'PriceTest#fail': 'FAILED'}, workflow.junit_results(results))
            xml.write_text('<testsuite><testcase classname="T" name="same()"/><testcase classname="T" name="same()"/></testsuite>')
            with self.assertRaises(ValueError):
                workflow.junit_results(results)

    def test_snapshot_passes_execution_environment_and_records_fresh_results(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory).resolve()
            (project / 'gradle/wrapper').mkdir(parents=True)
            (project / 'gradle/wrapper/gradle-wrapper.properties').write_text('version=9')
            wrapper = project / 'gradlew'
            wrapper.write_text('''#!/bin/sh
test "$REGRESSION_TEST_ENV" = "present" || exit 7
mkdir -p build/test-results/test
echo '<testsuite><testcase classname="PriceTest" name="boundary()"/></testsuite>' > build/test-results/test/TEST-PriceTest.xml
''')
            wrapper.chmod(0o700)
            result = workflow.snapshot(project, {'database': 'NOT_APPLICABLE', 'features': {'price': ['PriceTest#boundary']}},
                                       dict(os.environ, REGRESSION_TEST_ENV='present'))
            self.assertEqual(0, result['buildExitCode'])
            self.assertEqual({'PriceTest#boundary': 'PASSED'}, result['tests'])
            self.assertNotIn('REGRESSION_TEST_ENV', result['environment'])

    def test_cli_document_mode_never_calls_validator_or_build(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = Path(directory) / 'plan.json'
            plan.write_text('{"mode":"SPEC_ONLY","project":"/must-not-be-read"}')
            args = ['regression_workflow.py', 'baseline', '--plan', str(plan),
                    '--requirements', '/missing', '--analyzer', '/missing', '--output', '/missing']
            with patch('sys.argv', args), patch.object(workflow, 'validate_requirements') as validator, patch.object(workflow, 'snapshot') as execute:
                self.assertEqual(2, workflow.main())
                validator.assert_not_called()
                execute.assert_not_called()

    def test_completion_rejects_different_built_jar(self):
        snapshot = self.baseline()
        snapshot['artifactSha256'] = {'service.jar': 'final-build'}
        service = {'status': 'PASSED', 'sources': snapshot['sources'], 'jarSha256': 'stale-build',
                   'scenarios': dict.fromkeys(['normal', 'duplicate', 'concurrency', 'rollback'], 'PASSED'),
                   'processStopped': True}
        self.assertEqual('NEEDS_REVIEW', workflow.completion(self.compare(snapshot, snapshot), snapshot, service, [])['status'])

    def test_original_baseline_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'baseline.json'
            workflow.save(path, self.baseline(), exclusive=True)
            with self.assertRaises(FileExistsError):
                workflow.save(path, {'tests': {}}, exclusive=True)
            self.assertEqual(self.baseline(), workflow.read_json(path))

    def test_fixture_reproduction_success_preserves_unresolved_risk(self):
        snapshot = self.baseline()
        service = {'status': 'PASSED', 'sources': snapshot['sources'],
                   'scenarios': dict.fromkeys(['normal', 'duplicate', 'concurrency', 'rollback'], 'PASSED'),
                   'processStopped': True}
        reviewed = {'status': 'PASSED', 'targetRiskStatus': 'UNRESOLVED', 'blockingEvidence': [], 'items': []}
        result = workflow.completion(self.compare(snapshot, snapshot), snapshot, service, [], reviewed)
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual('PASSED', result['verificationStatus'])
        self.assertEqual('UNRESOLVED', result['targetRiskStatus'])

    def test_verified_execution_with_actual_test_gap_is_not_completed(self):
        snapshot = self.baseline()
        service = {'status': 'PASSED', 'sources': snapshot['sources'],
                   'scenarios': dict.fromkeys(['normal', 'duplicate', 'concurrency', 'rollback'], 'PASSED'),
                   'processStopped': True}
        reviewed = {'status': 'NEEDS_REVIEW', 'targetRiskStatus': 'UNRESOLVED', 'blockingEvidence': ['real-gap'], 'items': []}
        result = workflow.completion(self.compare(snapshot, snapshot), snapshot, service, [], reviewed)
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertEqual('PASSED', result['verificationStatus'])
        service['processStopped'] = False
        self.assertEqual('FAILED', workflow.completion(self.compare(snapshot, snapshot), snapshot, service, [], reviewed)['status'])

    def test_reviewed_completion_rejects_changed_live_execution_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory).resolve()
            (project/'gradle/wrapper').mkdir(parents=True)
            (project/'gradle/wrapper/gradle-wrapper.properties').write_text('version=9')
            (project/'src/main').mkdir(parents=True)
            source = project/'src/main/Main.java'
            source.write_text('class Main {}')
            wrapper = project/'gradlew'
            wrapper.write_text('''#!/bin/sh
mkdir -p build/test-results/test
echo '<testsuite><testcase classname="PriceTest" name="boundary()"/></testsuite>' > build/test-results/test/TEST-PriceTest.xml
''')
            wrapper.chmod(0o700)
            plan = {'database': 'NOT_APPLICABLE', 'features': {'price': ['PriceTest#boundary']}}
            snapshot = workflow.snapshot(project, plan)
            saved = project/'build/after.json'
            workflow.save(saved, {'snapshot': snapshot})
            self.assertEqual(snapshot, workflow.verified_snapshot(project, saved, plan))
            junit = project/'build/test-results/test/TEST-PriceTest.xml'
            original = junit.read_text()
            junit.write_text(original.replace('/>', '><skipped/></testcase>'))
            with self.assertRaises(ValueError): workflow.verified_snapshot(project, saved, plan)
            junit.write_text(original)
            source.write_text('class Main { int changed; }')
            with self.assertRaises(ValueError): workflow.verified_snapshot(project, saved, plan)


if __name__ == '__main__':
    unittest.main()
