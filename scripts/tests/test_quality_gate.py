import importlib.util
from pathlib import Path
import tempfile
import unittest


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1]/(name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


quality = module('quality_gate')
evidence = module('evidence_review')


class QualityGateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.project = Path(temporary.name).resolve()
        self.config = quality.settings({'targets': {'example.Boundary': 'src/main/java/example/Boundary.java'}})
        self.put(quality.COVERAGE_XML, '<report><package name="example"><class name="example/Boundary">'
                 '<counter type="LINE" missed="0" covered="2"/><counter type="BRANCH" missed="0" covered="2"/>'
                 '</class></package></report>')
        self.put(quality.COVERAGE_EXEC, 'fresh execution data')
        self.put('build/classes/java/main/example/Boundary.class', 'compiled main')
        self.put('build/classes/java/test/example/BoundaryTest.class', 'compiled tests')

    def put(self, path, content):
        target = self.project/path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def mutation(self, status='KILLED'):
        return {'outcome': 'MUTATION_COMPLETED', 'targetClasses': ['example.Boundary'],
                'mutations': [{'evidence': {'mutatedClass': 'example.Boundary', 'status': status}}]}

    def test_coverage_and_mutation_must_both_meet_explicit_thresholds(self):
        # given / when
        counters = quality.coverage(self.project, self.config)
        result = quality.evaluate(self.config, counters, self.mutation())
        # then
        self.assertEqual('PASSED', result['status'])
        self.assertEqual(1.0, result['mutation']['example.Boundary']['score'])
        for status in ('SURVIVED', 'NO_COVERAGE', 'TIMED_OUT', 'RUN_ERROR', 'UNKNOWN'):
            with self.subTest(status=status):
                result = quality.evaluate(self.config, counters, self.mutation(status))
                self.assertEqual('FAILED', result['status'])
                self.assertTrue(result['failures'])
        counters['example.Boundary']['LINE']['ratio'] = 0.1
        self.assertEqual('FAILED', quality.evaluate(self.config, counters, self.mutation())['status'])

    def test_absent_empty_unfinished_or_unrelated_mutation_does_not_pass(self):
        counters = quality.coverage(self.project, self.config)
        for mutation in ({'outcome': 'BUILD_FAILED'}, dict(self.mutation(), targetClasses=[]),
                         dict(self.mutation(), mutations=[])):
            with self.subTest(mutation=mutation):
                self.assertNotEqual('PASSED', quality.evaluate(self.config, counters, mutation)['status'])
        malformed = self.mutation()
        malformed['mutations'][0]['evidence']['mutatedClass'] = 'other.Class'
        with self.assertRaises(ValueError): quality.evaluate(self.config, counters, malformed)
        with self.assertRaises(ValueError): quality.evaluate(self.config, {}, self.mutation())

    def test_missing_duplicate_and_negative_coverage_are_rejected(self):
        original = (self.project/quality.COVERAGE_XML).read_text()
        for invalid in ('<report/>', '<report', original.replace('missed="0"', 'missed="-1"'),
                        original.replace('</class>', '<counter type="LINE" missed="0" covered="1"/></class>'),
                        original.replace('example/Boundary', 'other/Class')):
            with self.subTest(invalid=invalid):
                self.put(quality.COVERAGE_XML, invalid)
                with self.assertRaises(ValueError): quality.coverage(self.project, self.config)

    def test_receipt_binds_report_execution_data_mutation_and_both_class_directories(self):
        # given
        self.put('build/session/mutation.json', '{}')
        snapshot = {'sources': {'src/main/java/example/Boundary.java': 'source'}}
        artifacts = [quality.COVERAGE_XML, quality.COVERAGE_EXEC, 'build/session/mutation.json']
        receipt = {'version': 1, 'status': 'PASSED', 'snapshotSha256': evidence.fingerprint(snapshot),
                   'sources': snapshot['sources'], 'artifacts': {path: quality.digest(self.project/path) for path in artifacts},
                   'bytecode': quality.bytecode_hashes(self.project)}
        # when / then
        quality.fresh(self.project, receipt, snapshot, evidence.fingerprint)
        for path in artifacts + list(receipt['bytecode']):
            with self.subTest(path=path):
                original = (self.project/path).read_text()
                self.put(path, original + 'changed')
                with self.assertRaises(ValueError): quality.fresh(self.project, receipt, snapshot, evidence.fingerprint)
                self.put(path, original)
        with self.assertRaises(ValueError): quality.fresh(self.project, receipt, {'sources': {}}, evidence.fingerprint)

    def test_quality_paths_and_policy_cannot_escape_or_silently_default_targets(self):
        for value in ({}, {'targets': {}}, {'targets': {'C': '../outside.java'}},
                      dict(self.config, minimumLineCoverage=True), dict(self.config, minimumMutationScore=float('nan'))):
            with self.subTest(value=value), self.assertRaises(ValueError): quality.settings(value)
        (self.project/'link').symlink_to(self.project/'build', target_is_directory=True)
        for path in ('../outside', '/absolute', 'link/jacoco/test.exec'):
            with self.subTest(path=path), self.assertRaises(ValueError): quality.safe_file(self.project, path)


if __name__ == '__main__':
    unittest.main()
