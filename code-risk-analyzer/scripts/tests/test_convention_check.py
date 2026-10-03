import importlib.util
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('convention_check', Path(__file__).resolve().parents[1]/'convention_check.py')
convention = importlib.util.module_from_spec(spec)
spec.loader.exec_module(convention)


def report(**checks):
    values = dict({'architecture': 'PASS', 'tests': 'PASS', 'checkstyle': 'PASS'}, **checks)
    return {'status': 'FAIL' if 'FAIL' in values.values() else 'PASS', 'checks': values}


class ConventionTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.project = self.root/'demo'
        self.project.mkdir()
        self.commands = []

    def process(self, value=None, stderr=''):
        def run(command, **kwargs):
            self.commands.append((command, kwargs['cwd']))
            if value is not None:
                path = self.project/convention.REPORT
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(value))
            return subprocess.CompletedProcess(command, 1, stderr=stderr)
        return run

    def test_init_script_applies_published_plugin_by_id_without_editing_the_build(self):
        script = convention.init_script(convention.version())
        self.assertIn("classpath 'io.github.dochiri0916:build-convention:" + convention.version() + "'", script)
        self.assertIn('mavenLocal()', script)
        self.assertIn("pluginManager.withPlugin('java')", script)
        self.assertIn("!pluginManager.hasPlugin('io.github.dochiri0916.build-convention')", script)

    def test_run_uses_init_script_and_copies_the_report(self):
        # given
        output = self.root/'session'/'convention-after.json'
        # when
        value = convention.run(self.project, output, self.process(report(tests='FAIL')))
        # then
        command, cwd = self.commands[0]
        self.assertEqual(['./gradlew', 'check', '--continue', '--init-script'], command[:4])
        self.assertEqual(self.project, cwd)
        self.assertTrue(Path(command[4]).read_text().startswith('beforeProject'))
        self.assertEqual(value, json.loads(output.read_text()))

    def test_stale_report_is_never_reused(self):
        path = self.project/convention.REPORT
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(report()))
        stderr = 'FAILURE: Build failed\n\n* What went wrong:\nBuild convention verification must not be disabled.\n\n* Try:'
        with self.assertRaisesRegex(ValueError, 'must not be disabled'):
            convention.run(self.project, self.root/'out.json', self.process(None, stderr))

    def test_only_checks_the_change_broke_are_new(self):
        result = convention.compare(report(tests='FAIL'), report(tests='FAIL', architecture='FAIL'))
        self.assertEqual({'new': ['architecture'], 'existing': ['tests']}, result)
        self.assertEqual({'new': ['tests'], 'existing': []}, convention.compare(None, report(tests='FAIL')))

    def test_exit_code_blocks_new_failures_and_reports_existing_ones(self):
        before = self.root/'before.json'
        before.write_text(json.dumps(report(tests='FAIL')))
        cases = [(report(tests='FAIL'), 0, '변경 전부터 실패'), (report(tests='FAIL', checkstyle='FAIL'), 1, '이번 변경으로 실패 - Checkstyle')]
        for after, code, message in cases:
            with self.subTest(code=code):
                convention.run = self.runner(after)
                output = io.StringIO()
                with redirect_stdout(output):
                    self.assertEqual(code, convention.main([str(self.project), '--output', str(self.root/'a.json'), '--before', str(before)]))
                self.assertIn(message, output.getvalue())

    def test_unusable_before_report_counts_every_failure(self):
        convention.run = self.runner(report(tests='FAIL'))
        output = io.StringIO()
        with redirect_stdout(output):
            code = convention.main([str(self.project), '--output', str(self.root/'a.json'), '--before', str(self.root/'missing.json')])
        self.assertEqual(1, code)
        self.assertIn('모든 실패를 이번 결과로', output.getvalue())

    def runner(self, value):
        self.addCleanup(setattr, convention, 'run', ORIGINAL_RUN)
        return lambda project, output: value


ORIGINAL_RUN = convention.run


if __name__ == '__main__':
    unittest.main()
