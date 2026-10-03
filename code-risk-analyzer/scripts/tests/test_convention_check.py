import hashlib
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
        (self.project/'build/classes/java/main').mkdir(parents=True)
        # when
        value = convention.run(self.project, output, 'abc123', self.process(report(checkstyle='FAIL'), GRADLE_OUTPUT))
        # then
        self.assertEqual((['./gradlew', 'testClasses'], self.project), self.commands[0])
        command, cwd = self.commands[1]
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

    def test_never_built_project_is_not_compiled_first(self):
        convention.run(self.project, self.root/'out.json', None, self.process(report()))
        self.assertEqual(['./gradlew', 'check'], self.commands[0][0][:2])

    def test_stale_report_is_never_reused(self):
        path = self.project/convention.REPORT
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(report()))
        output = 'FAILURE: Build failed\n\n* What went wrong:\nBuild convention verification must not be disabled.\n\n* Try:'
        with self.assertRaisesRegex(ValueError, 'must not be disabled'):
            convention.run(self.project, self.root/'out.json', None, self.process(None, output))

    def test_aborted_check_fails_but_a_missing_plugin_is_only_unavailable(self):
        aborted = '* What went wrong:\nBuild convention verification must not be disabled.\n'
        missing = '* What went wrong:\nCould not resolve all dependencies.\n> Could not find io.github.dochiri0916:build-convention:1.1.0.\n'
        for output, code in ((aborted, 1), (missing, 2)):
            with self.subTest(code=code):
                original = convention.run
                convention.run = lambda project, out, base, text=output: original(project, out, base, self.process(None, text))
                self.addCleanup(setattr, convention, 'run', original)
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(code, convention.main([str(self.project), '--output', str(self.root/'a.json')]))
                convention.run = original

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

    def test_fix_is_undone_when_existing_tests_fail_and_retried_once_with_the_failure(self):
        # given
        original = (self.project/self.TEST).read_text()
        failed = lambda command, **kwargs: subprocess.CompletedProcess(
            command, 1, stdout='> Task :compileTestJava FAILED\nDemoApplicationTests.java:3: error: cannot find symbol\nBUILD FAILED', stderr='')
        # when
        result = cleanup.fix(self.project, self.session, [{'m': 1}], self.call(self.change(), self.change()), failed)
        # then
        self.assertEqual('FAILED', result['status'])
        self.assertIn('cleanup-rejected-*.json', result['reason'])
        self.assertEqual(original, (self.project/self.TEST).read_text())
        self.assertFalse((self.session/'cleanup.json').exists())
        self.assertIn('error: cannot find symbol', self.commands[1])
        rejected = json.loads((self.session/'cleanup-rejected-2.json').read_text())
        self.assertEqual(self.fixed, rejected['changes'][0]['content'])

    def test_second_cleanup_attempt_can_pass(self):
        codes = [1, 0]
        run = lambda command, **kwargs: subprocess.CompletedProcess(command, codes.pop(0), stdout='', stderr='')
        result = cleanup.fix(self.project, self.session, [{'m': 1}], self.call(self.change(), self.change()), run)
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual(self.fixed, (self.project/self.TEST).read_text())
        self.assertTrue((self.session/'cleanup-rejected-1.json').is_file())

    def test_full_annotation_names_keep_the_test_and_losing_tests_undoes_the_fix(self):
        # given: the model wrote fully qualified annotations
        self.fixed = self.fixed.replace('@Test', '@org.junit.jupiter.api.Test').replace(
            '@DisplayName', '@org.junit.jupiter.api.DisplayName')
        # when
        result = cleanup.fix(self.project, self.session, [{'m': 1}], self.call(self.change()), self.exit_code(0))
        # then
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual({'existing-tests': ['com.example.demo.DemoApplicationTests#애플리케이션이 뜬다']},
                         json.loads((self.session/'plan.json').read_text())['features'])

    def test_fix_that_hides_existing_tests_is_undone(self):
        original = (self.project/self.TEST).read_text()
        self.fixed = 'package com.example.demo;\nclass DemoApplicationTests {\n}\n'
        result = cleanup.fix(self.project, self.session, [{'m': 1}], self.call(self.change(), self.change()), self.exit_code(0))
        self.assertEqual('FAILED', result['status'])
        self.assertIn('1개에서 0개로', result['reason'])
        self.assertEqual(original, (self.project/self.TEST).read_text())

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


class ChangeFixTests(Temp):
    MAIN = 'src/main/java/com/example/demo/member/Member.java'
    TEST = 'src/test/java/com/example/demo/member/MemberTest.java'

    def setUp(self):
        super().setUp()
        self.session = self.project/'build/ask/s1'
        self.session.mkdir(parents=True)
        for path in (self.MAIN, self.TEST, 'src/main/java/com/example/demo/Other.java'):
            (self.project/path).parent.mkdir(parents=True, exist_ok=True)
            (self.project/path).write_text('class X {}\n')
        (self.session/'scope.json').write_text(json.dumps({'files': [self.MAIN, self.TEST]}))
        self.report = {'checks': {'architecture': 'FAIL'}, 'violations': [
            {'file': self.MAIN, 'message': 'domain must use ErrorCode-based exceptions'},
            {'file': self.TEST, 'message': "must include '// then'"},
            {'file': 'src/main/java/com/example/demo/Other.java', 'message': 'outside scope'}]}

    def call(self, value):
        def run(config, workspace, prompt, schema, validate):
            self.commands.append(prompt)
            return {'status': validate(value)['status'], 'output': value}
        return run

    def test_scope_production_files_are_fixed_through_the_workflow_implementation_stage(self):
        # given
        fixed = {'status': 'COMPLETED', 'changes': [{'path': self.MAIN, 'content': 'final class X {}\n'}], 'unknowns': []}

        def workflow(command, **kwargs):
            self.commands.append(command)
            return subprocess.CompletedProcess(command, 0)
        # when
        result = cleanup.fix_change(self.project, self.session, self.report, [{'m': 1}], self.call(fixed), workflow)
        # then
        self.assertEqual(('COMPLETED', [self.MAIN]), (result['status'], result['files']))
        self.assertEqual(['src/main/java/com/example/demo/Other.java', self.TEST], result['remaining'])
        self.assertIn('ErrorCode-based', self.commands[0])
        self.assertNotIn('outside scope', self.commands[0])
        command = self.commands[1]
        self.assertEqual(['implementation', '--config', str(self.session/'ask-config.json'), '--state',
                          str(self.session/'state.json'), '--changes'], command[2:8])
        changes = json.loads(Path(command[8]).read_text())['changes']
        self.assertEqual([{'path': self.MAIN, 'content': 'final class X {}\n',
                           'expectedSha256': hashlib.sha256(b'class X {}\n').hexdigest()}], changes)
        self.assertEqual('class X {}\n', (self.project/self.MAIN).read_text())

    def test_tests_and_files_outside_scope_are_never_offered(self):
        report = dict(self.report, violations=self.report['violations'][1:])
        result = cleanup.fix_change(self.project, self.session, report, [{'m': 1}], self.call(None))
        self.assertEqual('UNFIXABLE', result['status'])
        self.assertEqual([], self.commands)
        with self.assertRaises(ValueError):
            cleanup.validator([self.MAIN])({'status': 'COMPLETED', 'changes': [{'path': self.TEST, 'content': 'x'}], 'unknowns': []})


if __name__ == '__main__':
    unittest.main()
