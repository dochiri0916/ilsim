import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('agent_pick', Path(__file__).resolve().parents[1]/'agent_pick.py')
pick = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pick)
guidance = pick.guidance
FILES = {'conventions/README.md': b'guidance v1', 'templates/README.md': b'templates',
         'templates/src/main/java/example/Book.java': b'final class Book {}'}


class GuidanceTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def project(self, root=None):
        root = root or self.root
        root.mkdir(parents=True, exist_ok=True)
        (root/'gradlew').touch()
        (root/'build.gradle').write_text('')
        return root

    def test_preserves_user_instructions_and_only_updates_owned_block(self):
        agents = self.root/'AGENTS.md'
        agents.write_bytes('기존 지침\r\n'.encode())
        guidance.sync(self.root, '1.2.0', FILES)
        agents.write_bytes(agents.read_bytes() + '사용자 추가 지침\r\n'.encode())
        guidance.sync(self.root, '1.3.0', dict(FILES, **{'conventions/README.md': b'guidance v2'}))
        result = agents.read_bytes().decode()
        self.assertTrue(result.startswith('기존 지침\r\n'))
        self.assertTrue(result.endswith('사용자 추가 지침\r\n'))
        self.assertEqual(1, result.count(guidance.START))
        self.assertIn('1.3.0', result)
        manifest = json.loads((self.root/guidance.DESTINATION/guidance.MANIFEST).read_bytes())
        self.assertEqual('1.3.0', manifest['pluginVersion'])

    def test_unchanged_sync_does_not_rewrite_files(self):
        guidance.sync(self.root, '1.2.0', FILES)
        paths = [p for p in self.root.rglob('*') if p.is_file()]
        before = {p: p.stat().st_mtime_ns for p in paths}
        guidance.sync(self.root, '1.2.0', FILES)
        self.assertEqual(before, {p: p.stat().st_mtime_ns for p in paths})

    def test_local_edit_aborts_sync_without_overwriting_any_file(self):
        guidance.sync(self.root, '1.2.0', FILES)
        guide = self.root/guidance.DESTINATION/'conventions/README.md'
        guide.write_bytes(b'user edit')
        before = {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        with self.assertRaisesRegex(ValueError, '직접 수정'):
            guidance.sync(self.root, '1.3.0', dict(FILES, **{'new.md': b'new'}))
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_unmanaged_file_collision_is_preserved(self):
        path = self.root/guidance.DESTINATION/'templates/README.md'
        path.parent.mkdir(parents=True)
        path.write_bytes(b'user documentation')
        with self.assertRaises(ValueError):
            guidance.sync(self.root, '1.2.0', FILES)
        self.assertEqual(b'user documentation', path.read_bytes())
        self.assertFalse((self.root/'AGENTS.md').exists())

    def test_removes_only_obsolete_managed_files(self):
        guidance.sync(self.root, '1.2.0', dict(FILES, **{'obsolete.md': b'old'}))
        custom = self.root/guidance.DESTINATION/'custom.md'
        custom.write_bytes(b'user')
        guidance.sync(self.root, '1.3.0', FILES)
        self.assertFalse((custom.parent/'obsolete.md').exists())
        self.assertEqual(b'user', custom.read_bytes())

    def test_migrates_legacy_guidance_to_conventions_entrypoint(self):
        legacy = {name: b'old instructions' for name in
                  ('AGENTS_GUIDANCE.md', 'ARCHITECTURE.md', 'ERROR_HANDLING.md', 'TESTING.md')}
        guidance.sync(self.root, '1.2.0', legacy)
        agents = self.root/'AGENTS.md'
        agents.write_text(agents.read_text().replace('conventions/README.md', 'AGENTS_GUIDANCE.md'))
        result = guidance.sync(self.root, '1.2.0', FILES)
        self.assertTrue(result.endswith('/conventions/README.md'))
        for name in legacy:
            self.assertFalse((self.root/guidance.DESTINATION/name).exists())
        self.assertNotIn('AGENTS_GUIDANCE.md', agents.read_text())
        self.assertIn('conventions/README.md', agents.read_text())

    def test_malformed_agents_block_is_preserved(self):
        agents = self.root/'AGENTS.md'
        agents.write_text('user\n' + guidance.START)
        with self.assertRaisesRegex(ValueError, '손상'):
            guidance.sync(self.root, '1.2.0', FILES)
        self.assertFalse((self.root/guidance.DESTINATION).exists())

    def test_symlink_destination_is_not_followed(self):
        outside = self.root/'outside'
        outside.mkdir()
        (self.root/'docs').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, '심볼릭'):
            guidance.sync(self.root, '1.2.0', FILES)
        self.assertEqual([], list(outside.iterdir()))

    def test_bundle_uses_published_artifact_and_rejects_mismatched_version(self):
        jar = self.root/'plugin.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('build-convention-version.properties', 'version=1.2.0\n')
            for name, data in FILES.items():
                archive.writestr(guidance.PREFIX + name, data)
        self.assertEqual(FILES, guidance.bundle('1.2.0', jar))
        with self.assertRaisesRegex(ValueError, '버전'):
            guidance.bundle('1.3.0', jar)

    def test_unsafe_archive_and_manifest_paths_cannot_escape_destination(self):
        jar = self.root/'plugin.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('build-convention-version.properties', 'version=1.2.0\n')
            archive.writestr(guidance.PREFIX + '../AGENTS.md', b'overwrite')
        with self.assertRaisesRegex(ValueError, '경로'):
            guidance.bundle('1.2.0', jar)
        manifest = self.root/guidance.DESTINATION/guidance.MANIFEST
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps({'schemaVersion': 1, 'files': {'../../AGENTS.md': 'hash'}}))
        with self.assertRaisesRegex(ValueError, '경로'):
            guidance.sync(self.root, '1.2.0', FILES)
        self.assertFalse((self.root/'AGENTS.md').exists())

    def test_malformed_manifest_is_preserved_and_does_not_block_prepare(self):
        self.project()
        manifest = self.root/guidance.DESTINATION/guidance.MANIFEST
        manifest.parent.mkdir(parents=True)
        manifest.write_text('[]')
        lines = []
        with patch.object(guidance, 'bundle', return_value=FILES):
            self.assertEqual('', guidance.prepare(self.root, pick.find_project, lambda: '1.2.0', lines.append))
        self.assertEqual('[]', manifest.read_text())
        self.assertIn('관리', lines[0])
        self.assertFalse((self.root/'AGENTS.md').exists())

    def test_independent_projects_and_module_working_directory(self):
        first = self.project(self.root/'first')
        second = self.project(self.root/'second')
        ignored = self.project(self.root/'build'/'ignored')
        (self.root/'linked').symlink_to(ignored, target_is_directory=True)
        self.assertEqual([first, second], guidance.projects(self.root, pick.find_project))
        module = first/'module'
        module.mkdir()
        self.assertEqual([first], guidance.projects(module, pick.find_project))

    def test_unavailable_bundle_does_not_block_cli(self):
        self.project()
        lines = []
        with patch.object(guidance, 'bundle', side_effect=FileNotFoundError('missing jar')):
            self.assertEqual('', guidance.prepare(self.root, pick.find_project, lambda: '1.2.0', lines.append))
        self.assertIn('배치하지 못했습니다', lines[0])
        self.assertFalse((self.root/'AGENTS.md').exists())


if __name__ == '__main__':
    unittest.main()
