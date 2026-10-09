import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('autofix', SCRIPTS/'autofix.py')
autofix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(autofix)


class AutofixTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.project = Path(self.directory.name).resolve()
        source = self.project/'src/main/java/a'
        source.mkdir(parents=True)
        self.edited = source/'A.java'
        self.edited.write_text('import java.util.List;   \nclass A {}\n', encoding='utf-8')
        self.untouched = source/'B.java'
        self.untouched.write_text('class B {}  \n', encoding='utf-8')
        self.lines = []
        self.commands = []

    def process(self, command, **kwargs):
        self.commands.append(command)
        self.edited.write_text('class A {}\n', encoding='utf-8')
        return subprocess.CompletedProcess(command, 0, stdout='')

    def run_apply(self, edits=('src/main/java/a/A.java',), process=None, environ=None):
        return autofix.apply(self.project, list(edits), self.lines.append, process or self.process, environ or {})

    def test_reports_only_changed_edited_files(self):
        self.assertEqual(['src/main/java/a/A.java'], self.run_apply())
        self.assertIn('자동 수정: 1개 파일 정리 (src/main/java/a/A.java)', self.lines)
        command = self.commands[0]
        self.assertIn('spotlessApply', command)
        pattern = next(item for item in command if item.startswith('-PspotlessFiles='))
        self.assertIn(str(self.edited).replace('.java', r'\.java'), pattern)
        self.assertNotIn('B.java', pattern)

    def test_init_script_is_written_outside_sources(self):
        self.run_apply()
        self.assertTrue((self.project/'build/ai/autofix.init.gradle').is_file())

    def test_no_edited_java_means_no_gradle_run(self):
        self.assertEqual([], self.run_apply(edits=('README.md', 'src/main/java/a/Missing.java', '../outside/X.java')))
        self.assertEqual([], self.commands)

    def test_disabled_by_environment(self):
        self.assertEqual([], self.run_apply(environ={autofix.DISABLE: '1'}))
        self.assertEqual([], self.commands)

    def test_failure_keeps_going_and_reports_what_changed(self):
        def failing(command, **kwargs):
            return subprocess.CompletedProcess(command, 1, stdout='boom')
        self.assertEqual([], self.run_apply(process=failing))
        self.assertEqual(['자동 수정: 일부 실행 실패, 변경 없음'], self.lines)

    def test_timeout_is_not_fatal(self):
        def slow(command, **kwargs):
            raise subprocess.TimeoutExpired(command, 1)
        self.assertEqual([], self.run_apply(process=slow))
        self.assertEqual(['자동 수정: 건너뜀 (TimeoutExpired)'], self.lines)

    def test_format_option_adds_google_java_format(self):
        self.assertNotIn('googleJavaFormat', autofix.init_script())
        self.assertIn("googleJavaFormat('1.25.2').aosp()", autofix.init_script('aosp'))
        self.assertIn('hasPlugin(\'com.diffplug.spotless\')', autofix.init_script())


if __name__ == '__main__':
    unittest.main()
