import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('self_update', Path(__file__).resolve().parents[1]/'self_update.py')
self_update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(self_update)

GIT_ENV = {'GIT_AUTHOR_NAME': 't', 'GIT_AUTHOR_EMAIL': 't@t', 'GIT_COMMITTER_NAME': 't', 'GIT_COMMITTER_EMAIL': 't@t'}


def git(folder, *args):
    result = subprocess.run(['git', '-C', str(folder), *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, env=dict(os.environ, **GIT_ENV))
    if result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout.strip()


def commit(folder, files, message):
    for name, text in files.items():
        path = folder/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
    git(folder, 'add', '-A')
    git(folder, 'commit', '-q', '-m', message)


class Gradle:
    """Real git, recorded Gradle: the builds are the only thing these tests fake."""

    def __init__(self, fail=()):
        self.builds, self.fail = [], set(fail)

    def __call__(self, command, **options):
        if command[0] == './gradlew':
            project = Path(options['cwd']).name
            self.builds.append((project, command[1]))
            return subprocess.CompletedProcess(command, 1 if project in self.fail else 0, 'BUILD FAILED\n' if project in self.fail else '')
        return subprocess.run(command, **options)


class SelfUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.home = base/'home'
        self.home.mkdir()
        git(self.home, 'init', '-q', '-b', 'main')
        commit(self.home, {'code-risk-analyzer/src/A.java': 'a', 'code-risk-analyzer/scripts/agent_pick.py': '1',
                           'code-risk-analyzer/build.gradle': 'x', 'build-convention/src/B.java': 'b',
                           'build-convention/build.gradle': 'y'}, 'init')
        git(base, 'clone', '-q', str(self.home), 'work')
        self.work = base/'work'
        self.messages = []
        self.clock = [10_000.0]
        self.gradle = Gradle()
        self_update.mark_built(self.work)

    def tearDown(self):
        self.temp.cleanup()

    def update(self, gradle=None, **options):
        return self_update.update(self.work, run=gradle or self.gradle, now=lambda: self.clock[0], environ={},
                                  say=self.messages.append, **options)

    def record(self):
        return json.loads((self.work/'.git'/self_update.RECORD).read_text(encoding='utf-8'))

    def test_nothing_new_pulls_and_builds_nothing(self):
        # given: the checkout is current and everything was built by install
        # when
        pulled = self.update()
        # then
        self.assertFalse(pulled)
        self.assertEqual([], self.gradle.builds)

    def test_script_only_change_is_pulled_without_rebuilding(self):
        # given: home pushes a change to a script that runs straight from the checkout
        commit(self.home, {'code-risk-analyzer/scripts/agent_pick.py': '2'}, 'script')
        # when
        pulled = self.update()
        # then
        self.assertTrue(pulled)
        self.assertEqual('2', (self.work/'code-risk-analyzer/scripts/agent_pick.py').read_text(encoding='utf-8'))
        self.assertEqual([], self.gradle.builds)

    def test_source_change_rebuilds_only_that_project(self):
        # given
        commit(self.home, {'build-convention/src/B.java': 'b2'}, 'plugin')
        # when
        self.assertTrue(self.update())
        # then: only the plugin is published again, and a later request does not rebuild it
        self.assertEqual([('build-convention', 'publishToMavenLocal')], self.gradle.builds)
        self.clock[0] += self_update.INTERVAL_SECONDS
        self.update()
        self.assertEqual(1, len(self.gradle.builds))

    def test_fetch_waits_for_the_interval(self):
        # given: one check already happened
        self.update()
        commit(self.home, {'code-risk-analyzer/scripts/agent_pick.py': '2'}, 'script')
        # when: the next request comes within the interval
        pulled = self.update()
        # then: no fetch until the interval passes, unless forced
        self.assertFalse(pulled)
        self.assertTrue(self.update(force=True))

    def test_uncommitted_edits_block_the_pull(self):
        # given: the work checkout has a local edit to a tracked file
        commit(self.home, {'code-risk-analyzer/scripts/agent_pick.py': '2'}, 'script')
        (self.work/'code-risk-analyzer/scripts/agent_pick.py').write_text('local', encoding='utf-8')
        # when
        pulled = self.update()
        # then
        self.assertFalse(pulled)
        self.assertEqual('local', (self.work/'code-risk-analyzer/scripts/agent_pick.py').read_text(encoding='utf-8'))
        self.assertIn('커밋하지 않은 수정', self.messages[-1])

    def test_tracked_bytecode_rewritten_by_python_does_not_block(self):
        # given: an older commit tracked __pycache__, and running the scripts rewrote it
        commit(self.home, {'code-risk-analyzer/scripts/__pycache__/ask.cpython-311.pyc': 'old'}, 'tracked cache')
        self.update(force=True)
        (self.work/'code-risk-analyzer/scripts/__pycache__/ask.cpython-311.pyc').write_text('regenerated', encoding='utf-8')
        git(self.home, 'rm', '-q', '-r', '--cached', 'code-risk-analyzer/scripts/__pycache__')
        git(self.home, 'commit', '-q', '-m', 'untrack cache')
        # when
        pulled = self.update(force=True)
        # then: the cache file no longer stands in the way of the commit that untracks it
        self.assertTrue(pulled)
        self.assertEqual(git(self.home, 'rev-parse', 'HEAD'), git(self.work, 'rev-parse', 'HEAD'))

    def test_diverged_checkout_is_left_alone(self):
        # given: both sides committed
        commit(self.home, {'code-risk-analyzer/scripts/agent_pick.py': '2'}, 'home')
        commit(self.work, {'note.txt': 'work'}, 'work')
        head = git(self.work, 'rev-parse', 'HEAD')
        # when
        pulled = self.update()
        # then
        self.assertFalse(pulled)
        self.assertEqual(head, git(self.work, 'rev-parse', 'HEAD'))
        self.assertIn('갈라졌습니다', self.messages[-1])

    def test_unreachable_remote_runs_the_current_version(self):
        # given: the remote is gone
        git(self.work, 'remote', 'set-url', 'origin', str(Path(self.temp.name)/'missing'))
        # when
        pulled = self.update()
        # then: the failure is reported once and recorded so the next request does not wait on the network
        self.assertFalse(pulled)
        self.assertIn('업데이트 확인 실패', self.messages[-1])
        self.assertEqual(self.clock[0], self.record()['checkedAt'])

    def test_failed_build_keeps_the_old_artifact_and_is_not_retried(self):
        # given: a pushed analyzer change that does not build
        commit(self.home, {'code-risk-analyzer/src/A.java': 'broken'}, 'broken')
        gradle = Gradle(fail={'code-risk-analyzer'})
        # when
        self.update(gradle)
        self.clock[0] += self_update.INTERVAL_SECONDS
        self.update(gradle)
        # then: one attempt per broken version, and the next fixed push builds again
        self.assertEqual([('code-risk-analyzer', 'bootJar')], gradle.builds)
        self.assertIn('빌드에 실패했습니다', self.messages[-1])
        commit(self.home, {'code-risk-analyzer/src/A.java': 'fixed'}, 'fixed')
        self.clock[0] += self_update.INTERVAL_SECONDS
        self.update()
        self.assertEqual([('code-risk-analyzer', 'bootJar')], self.gradle.builds)
        self.assertNotIn('code-risk-analyzer', self.record()['failed'])

    def test_no_record_rebuilds_both_once(self):
        # given: a checkout installed before automatic updates existed
        (self.work/'.git'/self_update.RECORD).unlink()
        # when
        self.update()
        # then
        self.assertEqual({'code-risk-analyzer', 'build-convention'}, {project for project, _ in self.gradle.builds})

    def test_switched_off_by_environment(self):
        commit(self.home, {'code-risk-analyzer/scripts/agent_pick.py': '2'}, 'script')
        pulled = self_update.update(self.work, run=self.gradle, environ={'ASK_NO_UPDATE': '1'})
        self.assertFalse(pulled)
        self.assertEqual('1', (self.work/'code-risk-analyzer/scripts/agent_pick.py').read_text(encoding='utf-8'))

    def test_not_a_checkout_does_nothing(self):
        folder = Path(self.temp.name)/'plain'
        folder.mkdir()
        self.assertFalse(self_update.update(folder, run=self.gradle, environ={}))


if __name__ == '__main__':
    unittest.main()
