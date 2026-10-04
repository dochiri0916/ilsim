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

    def test_validators_choose_groups_and_only_explicit_ones_are_required(self):
        unset = {'architecture': None, 'convention': None}
        cases = [
            (unset, None, ['architecture', 'convention'], {}, []),
            ({'architecture': False, 'convention': None}, None, ['convention'], {'architecture'}, []),
            ({'architecture': None, 'convention': False}, None, [], {'architecture', 'convention'}, []),
            ({'architecture': True, 'convention': False}, None, ['architecture'], {'convention'}, ['architecture']),
            ({'architecture': None, 'convention': False}, True, ['architecture', 'convention'], {}, ['convention']),
            ({'architecture': True, 'convention': True}, False, [], {'architecture', 'convention'}, []),
        ]
        for configured, requested, enabled, off, required in cases:
            with self.subTest(configured=configured, requested=requested):
                chosen = convention.selection(configured, requested)
                self.assertEqual((enabled, set(off), required), (chosen['enabled'], set(chosen['off']), chosen['required']))
        self.assertIn('validators.architecture.enabled: false',
                      convention.selection({'architecture': False, 'convention': None})['off']['architecture'])

    def test_switched_off_architecture_is_recorded_as_skipped_and_does_not_block(self):
        # given: architecture and checkstyle both fail, and architecture is switched off
        value = report(architecture='FAIL', archUnit='FAIL', checkstyle='FAIL')
        reason = '.code-risk.yml validators.architecture.enabled: false'
        # when
        result = convention.run(self.project, self.root/'out.json', process=self.process(value, GRADLE_OUTPUT),
                                off={'architecture': reason})
        # then: only checkstyle blocks; ARCH violations are not offered to the fixer
        self.assertEqual({'architecture': reason, 'archUnit': reason}, result['skipped'])
        self.assertEqual(['checkstyle'], convention.blocking(result))
        self.assertEqual([None], [item.get('rule') for item in result['violations']])
        self.assertIn('설정으로 건너뜀(SKIP): 아키텍처, 아키텍처(ArchUnit) - ' + reason, convention.summary(result))

    def test_settings_come_from_the_analyzer_jar_only_when_the_project_has_a_config(self):
        calls = []

        def jar(command, **kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, stdout='{"architecture":false,"convention":null}\n')
        self.assertEqual({'architecture': None, 'convention': None}, convention.settings(self.project, process=jar))
        self.assertEqual([], calls)
        (self.project/'.code-risk.yml').write_text('validators:\n  architecture: {enabled: false}\n')
        fake = self.root/'analyzer.jar'
        fake.write_text('')
        self.assertEqual({'architecture': False, 'convention': None}, convention.settings(self.project, fake, jar))
        self.assertEqual(['validators', str(self.project)], calls[0][3:])

        def invalid(command, **kwargs):
            return subprocess.CompletedProcess(command, 2, stdout='Error: validators.architecture.enabled must be true or false\n')
        with self.assertRaisesRegex(ValueError, 'must be true or false'):
            convention.settings(self.project, fake, invalid)
        with self.assertRaisesRegex(ValueError, '분석기 jar'):
            convention.settings(self.project, self.root/'missing.jar', jar)

    def test_explicitly_enabled_but_unavailable_fails_instead_of_skipping(self):
        def unavailable(*args, **kwargs):
            raise convention.Unavailable('build-convention을 찾지 못했습니다')
        for configured, code in (({'architecture': None, 'convention': None}, 2), ({'architecture': None, 'convention': True}, 1)):
            with self.subTest(configured=configured):
                originals = convention.run, convention.settings
                convention.run, convention.settings = unavailable, lambda project: configured
                try:
                    with redirect_stdout(io.StringIO()):
                        self.assertEqual(code, convention.main([str(self.project), '--output', str(self.root/'a.json')]))
                finally:
                    convention.run, convention.settings = originals

    def test_both_groups_off_skips_without_running_gradle(self):
        originals = convention.run, convention.settings
        convention.run = lambda *args, **kwargs: self.fail('Gradle must not run')
        convention.settings = lambda project: {'architecture': None, 'convention': False}
        try:
            with redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, convention.main([str(self.project), '--output', str(self.root/'a.json')]))
        finally:
            convention.run, convention.settings = originals
        self.assertIn('SKIP', output.getvalue())

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


if __name__ == '__main__':
    unittest.main()
