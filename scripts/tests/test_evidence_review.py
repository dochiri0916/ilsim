import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('evidence_review', Path(__file__).resolve().parents[1] / 'evidence_review.py')
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class EvidenceReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name).resolve()
        self.write('src/main/Service.java', 'class Service {}\n')
        self.write('docs/spec.md', 'REQ-1: one committed row\n')
        self.write('src/test/ServiceTest.java', 'class ServiceTest {\n void saves() {\n  assertEquals(1, count());\n }\n void other() {\n  assertEquals(9, count());\n }\n}\n')
        self.snapshot = {'project': str(self.project), 'sources': {
            path: review.digest(self.project / path) for path in ['src/main/Service.java', 'src/test/ServiceTest.java']},
            'tests': {'ServiceTest#saves': 'PASSED'}, 'junitSha256': {'TEST-ServiceTest.xml': 'junit-hash'}}
        self.report = {'findings': [{'ruleId': 'TEST-001', 'status': 'REVIEW', 'severity': 'HIGH',
            'location': {'file': 'src/main/Service.java', 'startLine': 1},
            'evidence': [{'type': 'TEST_OBLIGATION', 'attributes': {'obligationId': 'OBL-1', 'coverageStatus': 'PARTIAL', 'riskId': '', 'tests': ['ServiceTest#saves']}}]}]}
        self.write('build/report.json', json.dumps(self.report))
        self.record = {'id': 'OBL-1', 'category': 'STATIC_LIMITATION', 'decision': 'VERIFIED',
            'reviewer': 'reviewer', 'reason': 'DB row count asserted against contract',
            'sourceSha256': self.snapshot['sources']['src/main/Service.java'],
            'requirement': {'id': 'REQ-1', 'path': 'docs/spec.md', 'sha256': review.digest(self.project/'docs/spec.md'), 'line': 1, 'text': 'REQ-1: one committed row'},
            'tests': [{'test': 'ServiceTest#saves', 'path': 'src/test/ServiceTest.java',
                'sha256': self.snapshot['sources']['src/test/ServiceTest.java'], 'methodLine': 2,
                'assertions': [{'line': 3, 'text': 'assertEquals(1, count());'}],
                'junit': {'file': 'TEST-ServiceTest.xml', 'sha256': 'junit-hash'}}]}

    def write(self, path, content):
        target = self.project/path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def bundle(self):
        return {'version': 1, 'snapshotSha256': review.fingerprint(self.snapshot),
                'reportSha256': review.digest(self.project/'build/report.json'), 'records': [self.record]}

    def run_review(self, bundle=None, policy=None):
        return review.review_evidence(self.project, self.snapshot, 'build/report.json', bundle or self.bundle(), policy)

    def test_verified_review_preserves_static_status(self):
        result = self.run_review()
        self.assertEqual('PASSED', result['status'])
        self.assertEqual('PARTIAL', result['items'][0]['staticStatus'])
        self.assertEqual('VERIFIED', result['items'][0]['review']['decision'])
        self.assertTrue(result['items'][0]['accepted'])
        self.assertEqual(self.report, json.loads((self.project/'build/report.json').read_text()))

    def test_existing_test_does_not_automatically_prove_adequacy(self):
        result = self.run_review({**self.bundle(), 'records': []})
        self.assertEqual('UNCLASSIFIED', result['items'][0]['category'])
        self.assertEqual('NEEDS_REVIEW', result['status'])

    def test_actual_test_gap_remains_blocking_even_with_passing_test(self):
        self.record.update(category='TEST_GAP', decision='INSUFFICIENT')
        result = self.run_review()
        self.assertEqual('TEST_GAP', result['items'][0]['category'])
        self.assertFalse(result['items'][0]['accepted'])

    def test_stale_source_test_requirement_execution_and_report_rejected(self):
        for field in ['source', 'test', 'requirement', 'execution', 'report']:
            with self.subTest(field=field):
                bundle = json.loads(json.dumps(self.bundle()))
                record = bundle['records'][0]
                if field == 'source': record['sourceSha256'] = 'stale'
                if field == 'test': record['tests'][0]['sha256'] = 'stale'
                if field == 'requirement': record['requirement']['sha256'] = 'stale'
                if field == 'execution': bundle['snapshotSha256'] = 'stale'
                if field == 'report': bundle['reportSha256'] = 'stale'
                self.assertEqual('NEEDS_REVIEW', self.run_review(bundle)['status'])

    def test_assertion_from_another_method_cannot_support_selected_test(self):
        self.record['tests'][0]['assertions'] = [{'line': 6, 'text': 'assertEquals(9, count());'}]
        self.assertFalse(self.run_review()['items'][0]['accepted'])

    def test_skip_missing_assertion_and_empty_reviewer_block_acceptance(self):
        self.snapshot['tests']['ServiceTest#saves'] = 'SKIPPED'
        self.assertFalse(self.run_review()['items'][0]['accepted'])
        self.snapshot['tests']['ServiceTest#saves'] = 'PASSED'
        self.record['tests'][0]['assertions'] = []
        self.assertFalse(self.run_review()['items'][0]['accepted'])
        self.record['reviewer'] = ''
        self.assertFalse(self.run_review()['items'][0]['accepted'])

    def test_general_production_risk_stays_blocking_even_when_test_verified(self):
        self.report['findings'][0].update(ruleId='TX-001', status='CONFIRMED', evidence=[])
        self.write('build/report.json', json.dumps(self.report))
        self.record['id'] = 'risk:TX-001:src/main/Service.java:1'
        result = self.run_review()
        self.assertEqual('UNRESOLVED', result['targetRiskStatus'])
        self.assertEqual('NEEDS_REVIEW', result['status'])

    def test_intentional_defect_requires_separate_project_bound_allowlist(self):
        self.report['findings'][0].update(ruleId='TX-001', status='CONFIRMED', evidence=[])
        self.write('build/report.json', json.dumps(self.report))
        self.record.update(id='risk:TX-001:src/main/Service.java:1', category='INTENTIONAL_DEFECT', decision='REPRODUCED')
        self.assertFalse(self.run_review()['items'][0]['accepted'])
        policy = {'version': 1, 'project': str(self.project), 'kind': 'FIXTURE', 'allowedItems': [{
            'id': self.record['id'], 'sourceSha256': self.record['sourceSha256'],
            'requirementSha256': self.record['requirement']['sha256']}]}
        result = self.run_review(policy=policy)
        self.assertTrue(result['items'][0]['accepted'])
        self.assertEqual('PASSED', result['status'])
        self.assertEqual('UNRESOLVED', result['targetRiskStatus'])
        policy['project'] = '/other'
        self.assertFalse(self.run_review(policy=policy)['items'][0]['accepted'])

    def test_duplicate_reviews_and_unknown_targets_are_invalid(self):
        bundle = self.bundle()
        bundle['records'].append(dict(self.record))
        with self.assertRaises(ValueError): self.run_review(bundle)
        bundle['records'] = [{**self.record, 'id': 'unknown'}]
        with self.assertRaises(ValueError): self.run_review(bundle)

    def test_path_traversal_and_symlinks_never_supply_evidence(self):
        self.record['requirement']['path'] = '../outside'
        self.assertFalse(self.run_review()['items'][0]['accepted'])
        (self.project/'docs/link.md').symlink_to(self.project/'docs/spec.md')
        self.record['requirement']['path'] = 'docs/link.md'
        self.assertFalse(self.run_review()['items'][0]['accepted'])

    def test_execution_report_from_another_class_cannot_support_test(self):
        self.snapshot['junitSha256']['TEST-OtherTest.xml'] = 'other'
        self.record['tests'][0]['junit'] = {'file': 'TEST-OtherTest.xml', 'sha256': 'other'}
        self.assertFalse(self.run_review()['items'][0]['accepted'])

    def test_malformed_fixture_policy_is_rejected(self):
        with self.assertRaises(ValueError): self.run_review(policy={'version': 1, 'allowedItems': ['invalid']})

    def test_analysis_diagnostics_cannot_disappear_from_review_gate(self):
        self.report['diagnostics'] = [{'code': 'ANALYSIS_INCOMPLETE', 'severity': 'ERROR'}]
        self.write('build/report.json', json.dumps(self.report))
        result = self.run_review()
        self.assertEqual('NEEDS_REVIEW', result['status'])
        self.assertEqual(self.report['diagnostics'], result['analysisDiagnostics'])


if __name__ == '__main__':
    unittest.main()
