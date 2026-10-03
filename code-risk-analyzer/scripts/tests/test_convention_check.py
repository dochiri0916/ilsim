import importlib.util
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


convention = load('convention_check')
cleanup = load('convention_fix')

GRADLE_OUTPUT = '''> Task :checkstyleMain FAILED
[ant:checkstyle] [ERROR] {project}/src/main/java/com/example/demo/DemoApplication.java:9:1: File contains tab characters (this is the first instance). [FileTabCharacter]
> Architecture convention violations:
   - [ARCH-TEST-CONVENTION] src/test/java/com/example/demo/DemoApplicationTests.java test method 'contextLoads' must declare @DisplayName in Korean
   - [ARCH-TEST-CONVENTION] src/test/java/com/example/demo/DemoApplicationTests.java test method 'contextLoads' must declare @DisplayName in Korean
'''


def report(**checks):
    values = dict({'architecture': 'PASS', 'tests': 'PASS', 'checkstyle': 'PASS'}, **checks)
    return {'status': 'FAIL' if 'FAIL' in values.values() else 'PASS', 'checks': values}


class Temp(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.project = self.root/'demo'
        self.project.mkdir()
        self.commands = []


class ConventionTests(Temp):
    def process(self, value=None, output=''):
        def run(command, **kwargs):
            self.commands.append((command, kwargs['cwd']))
            if value is not None:
                path = self.project/convention.REPORT
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(value))
            return subprocess.CompletedProcess(command, 1, stdout=output.format(project=self.project))
        return run

    def main(self, value, *extra):
        original = convention.run
        convention.run = lambda project, output, base_ref: value
        self.addCleanup(setattr, convention, 'run', original)
        with redirect_stdout(io.StringIO()) as output:
            code = convention.main([str(self.project), '--output', str(self.root/'a.json'), *extra])
        return code, output.getvalue()

    def test_init_script_applies_published_plugin_by_id_without_editing_the_build(self):
        script = convention.init_script(convention.version())
        self.assertIn("classpath 'io.github.dochiri0916:build-convention:" + convention.version() + "'", script)
        self.assertIn('mavenLocal()', script)
        self.assertIn("pluginManager.withPlugin('java')", script)
        self.assertIn("!pluginManager.hasPlugin('io.github.dochiri0916.build-convention')", script)

    def test_run_saves_report_with_printed_violations_and_base_ref(self):
        # given
        output = self.root/'session'/'convention-after.json'
        # when
        value = convention.run(self.project, output, 'abc123', self.process(report(checkstyle='FAIL'), GRADLE_OUTPUT))
        # then
        command, cwd = self.commands[0]
        self.assertEqual(['./gradlew', 'check', '--continue', '--init-script'], command[:4])
        self.assertEqual('-PchangedCoverageBaseRef=abc123', command[-1])
        self.assertEqual(self.project, cwd)
        self.assertTrue(Path(command[4]).read_text().startswith('beforeProject'))
        self.assertEqual(value, json.loads(output.read_text()))
        self.assertEqual([
            {'file': 'src/main/java/com/example/demo/DemoApplication.java', 'line': 9,
             'message': 'File contains tab characters (this is the first instance). [FileTabCharacter]'},
            {'file': 'src/test/java/com/example/demo/DemoApplicationTests.java', 'rule': 'ARCH-TEST-CONVENTION',
             'message': "test method 'contextLoads' must declare @DisplayName in Korean"}], value['violations'])

    def test_stale_report_is_never_reused(self):
        path = self.project/convention.REPORT
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(report()))
        output = 'FAILURE: Build failed\n\n* What went wrong:\nBuild convention verification must not be disabled.\n\n* Try:'
        with self.assertRaisesRegex(ValueError, 'must not be disabled'):
            convention.run(self.project, self.root/'out.json', None, self.process(None, output))

    def test_every_failing_check_blocks_and_earlier_failures_are_named(self):
        before = self.root/'before.json'
        before.write_text(json.dumps(report(tests='FAIL')))
        code, output = self.main(dict(report(tests='FAIL', checkstyle='FAIL'), baseRef='abc'), '--before', str(before))
        self.assertEqual(1, code)
        self.assertIn('실패 - Checkstyle, 테스트', output)
        self.assertIn('변경 전부터 실패하던 검사: 테스트', output)
        self.assertEqual((0, '컨벤션 검증: 통과'), (self.main(report())[0], self.main(report())[1].splitlines()[0]))

    def test_changed_coverage_without_git_is_reported_not_blocking(self):
        code, output = self.main(dict(report(changedCodeCoverage='FAIL'), baseRef=None))
        self.assertEqual(0, code)
        self.assertIn('Git 저장소가 아니어서', output)
        self.assertEqual(1, self.main(dict(report(changedCodeCoverage='FAIL'), baseRef='abc'))[0])


class CleanupTests(Temp):
    TEST = 'src/test/java/com/example/demo/DemoApplicationTests.java'

    def setUp(self):
        super().setUp()
        self.session = self.project/'build/ask/s1'
        self.session.mkdir(parents=True)
        (self.project/self.TEST).parent.mkdir(parents=True)
        (self.project/self.TEST).write_text('package com.example.demo;\nclass DemoApplicationTests {\n\t@Test\n\tvoid contextLoads() {\n\t}\n}\n')
        (self.session/'plan.json').write_text(json.dumps({'features': {'existing-tests': ['com.example.demo.DemoApplicationTests#contextLoads']}}))
        violations = [{'file': self.TEST, 'line': 3, 'message': 'tab'}, {'file': 'build.gradle', 'message': 'x'},
                      {'file': 'src/main/../../etc/passwd', 'message': 'x'}]
        (self.session/'convention-before.json').write_text(json.dumps(dict(report(checkstyle='FAIL'), violations=violations)))
        self.fixed = ('package com.example.demo;\nclass DemoApplicationTests {\n    @Test\n    @DisplayName("애플리케이션이 뜬다")\n'
                      '    void contextLoads() {\n    }\n}\n')

    def call(self, *outputs):
        outputs = list(outputs)

        def run(config, workspace, prompt, schema, validate):
            self.commands.append(prompt)
            value = outputs.pop(0)
            try:
                return {'status': validate(value)['status'], 'output': value}
            except ValueError:
                return {'status': 'INVALID_OUTPUT', 'failureKind': 'OUTPUT_CONTRACT'}
        return run

    def exit_code(self, code):
        return lambda command, **kwargs: subprocess.CompletedProcess(command, code)

    def change(self, path=None):
        return {'status': 'COMPLETED', 'changes': [{'path': path or self.TEST, 'content': self.fixed}], 'unknowns': []}

    def test_only_reported_source_files_are_offered(self):
        report_value = json.loads((self.session/'convention-before.json').read_text())
        self.assertEqual([self.TEST], cleanup.targets(self.project, report_value))

    def test_fix_rewrites_reported_files_and_refreshes_test_names_after_tests_pass(self):
        # when
        result = cleanup.fix(self.project, self.session, [{'m': 1}, {'m': 2}],
                             self.call(self.change('src/main/Other.java'), self.change()), self.exit_code(0))
        # then
        self.assertEqual(('COMPLETED', [self.TEST]), (result['status'], result['files']))
        self.assertEqual(2, len(self.commands))
        self.assertIn('\tvoid contextLoads()', self.commands[0])
        self.assertEqual(self.fixed, (self.project/self.TEST).read_text())
        self.assertEqual({'existing-tests': ['com.example.demo.DemoApplicationTests#애플리케이션이 뜬다']},
                         json.loads((self.session/'plan.json').read_text())['features'])
        self.assertIn('\t@Test', (self.session/'cleanup-original'/self.TEST).read_text())
        self.assertEqual(result, cleanup.fix(self.project, self.session, [], None))

    def test_fix_is_undone_when_existing_tests_fail(self):
        original = (self.project/self.TEST).read_text()
        result = cleanup.fix(self.project, self.session, [{'m': 1}], self.call(self.change()), self.exit_code(1))
        self.assertEqual('FAILED', result['status'])
        self.assertEqual(original, (self.project/self.TEST).read_text())
        self.assertFalse((self.session/'cleanup.json').exists())

    def test_nothing_reported_or_no_report_skips_without_a_model_call(self):
        (self.session/'convention-before.json').write_text(json.dumps(report()))
        self.assertEqual('SKIPPED', cleanup.fix(self.project, self.session, [{'m': 1}], self.call())['status'])
        (self.session/'convention-before.json').unlink()
        self.assertEqual('SKIPPED', cleanup.fix(self.project, self.session, [{'m': 1}], self.call())['status'])
        self.assertEqual([], self.commands)

    def test_questions_stop_without_touching_files(self):
        question = {'status': 'NEEDS_REVIEW', 'changes': [], 'unknowns': ['동작이 바뀝니다']}
        result = cleanup.fix(self.project, self.session, [{'m': 1}], self.call(question), self.exit_code(0))
        self.assertEqual(('NEEDS_REVIEW', ['동작이 바뀝니다']), (result['status'], result['questions']))
        self.assertIn('\t@Test', (self.project/self.TEST).read_text())


if __name__ == '__main__':
    unittest.main()
