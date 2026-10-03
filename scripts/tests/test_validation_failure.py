import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('validation_failure', Path(__file__).resolve().parents[1]/'validation_failure.py')
failure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(failure)


class ValidationFailureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name).resolve()
        path = self.project/'src/main/java/example/Service.java'
        path.parent.mkdir(parents=True)
        path.write_text('package example; class Service {}')
        self.results = self.project/'reports'
        self.results.mkdir()
        self.before = {'sources': {'src/test/T.java': 'same'}, 'environment': {'java': '25'}, 'tests': {'T#works': 'PASSED'}}
        self.after = dict(self.before, tests={'T#works': 'FAILED'}, buildExitCode=1)
        self.regression = {'status': 'FAILED', 'openEvidence': [], 'protectedChanges': []}

    def test_real_junit_assertions_and_production_runtime_locations_are_classified_without_messages(self):
        # given
        xml = '<testsuite><testcase classname="T" name="works()"><failure type="org.opentest4j.AssertionFailedError">private-key</failure></testcase></testsuite>'
        (self.results/'TEST-T.xml').write_text(xml)
        # when
        details = failure.junit_failures(self.results, self.project)
        result = failure.classify(self.before, dict(self.after, testFailures=details), self.regression)
        # then
        self.assertEqual('ASSERTION', result['kind'])
        self.assertTrue(result['retryable'])
        self.assertNotIn('private-key', str(details))
        (self.results/'TEST-T.xml').write_text(xml.replace('org.opentest4j.AssertionFailedError', 'java.lang.NullPointerException')
                                              .replace('private-key', 'at example.Service.call(Service.java:1)'))
        details = failure.junit_failures(self.results, self.project)
        self.assertEqual('TEST_RUNTIME', details[0]['kind'])
        self.assertEqual(['src/main/java/example/Service.java:1'], details[0]['locations'])

    def test_only_existing_production_compiler_locations_can_trigger_repair(self):
        # given / when
        result = failure.gradle_failure(str(self.project)+'/src/main/java/example/Service.java:1: error: private-key', self.project)
        # then
        self.assertEqual('COMPILATION', result['kind'])
        self.assertNotIn('private-key', str(result))
        self.assertEqual('UNKNOWN', failure.gradle_failure('src/test/java/T.java:1: error: cannot find symbol', self.project)['kind'])
        self.assertEqual('UNKNOWN', failure.gradle_failure('src/main/../../escape.java:1: error: failed', self.project)['kind'])

    def test_authentication_balance_and_environment_override_compiler_like_text(self):
        for text, expected in [('HTTP 401 unauthorized', 'AUTHENTICATION'), ('402 insufficient balance', 'BILLING'),
                               ('Could not resolve a dependency', 'ENVIRONMENT'), ('Connection refused', 'ENVIRONMENT')]:
            with self.subTest(text=text):
                # when
                result = failure.gradle_failure(text+'\nsrc/main/java/example/Service.java:1: error: failure', self.project)
                # then
                self.assertEqual(expected, result['kind'])
                self.assertFalse(result['retryable'])

    def test_stale_input_missing_skipped_and_unreviewed_contracts_never_trigger_repair(self):
        for update, regression, expected in [({'observedSources': {}}, self.regression, 'INPUTS'),
                                              ({'environment': {'java': '26'}}, self.regression, 'ENVIRONMENT'),
                                              ({'tests': {}}, self.regression, 'EVIDENCE'),
                                              ({'tests': {'T#works': 'SKIPPED'}}, self.regression, 'EVIDENCE'),
                                              ({}, dict(self.regression, openEvidence=['contract changed']), 'EVIDENCE')]:
            with self.subTest(update=update):
                # when
                result = failure.classify(self.before, dict(self.after, **update), regression)
                # then
                self.assertEqual(expected, result['kind'])
                self.assertFalse(result['retryable'])

    def test_missing_junit_can_only_retry_a_proven_main_compilation_error(self):
        # given
        after = dict(self.after, tests={}, evidenceError='No unambiguous fresh JUnit results',
                     executionFailure=failure.outcome('COMPILATION', locations=['src/main/java/example/Service.java:1']))
        # when / then
        self.assertEqual('COMPILATION', failure.classify(self.before, after, self.regression)['kind'])
        after['executionFailure'] = failure.outcome('UNKNOWN')
        self.assertFalse(failure.classify(self.before, after, self.regression)['retryable'])


if __name__ == '__main__':
    unittest.main()
