import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from resolve_codex_catalog_version import resolve, stamp_workspace, validate


class CatalogVersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.name', 'CI')
        self.git('config', 'user.email', 'ci@example.invalid')
        self.manifest = self.repo / 'codex-rs/Cargo.toml'
        self.manifest.parent.mkdir()
        self.write_version('0.0.0')
        self.base = self.commit('base', 1)

    def git(self, *args, date=None):
        env = os.environ.copy()
        if date:
            env.update(GIT_AUTHOR_DATE=date, GIT_COMMITTER_DATE=date)
        return subprocess.check_output(['git', '-C', str(self.repo), *args], env=env, text=True).strip()

    def write_version(self, version):
        self.manifest.write_text(f'[workspace.package]\nversion = "{version}"\nedition = "2024"\n')

    def commit(self, name, day):
        self.git('add', '.')
        self.git('commit', '-qm', name, date=f'2026-11-{day:02d}T12:00:00Z')
        return self.git('rev-parse', 'HEAD')

    def release(self, version, day, change_code=False):
        self.git('switch', '-q', '--detach', self.base)
        self.write_version(version)
        if change_code:
            (self.repo / 'new-code').write_text('unrelated feature')
        release = self.commit('release', day)
        self.git('tag', 'rust-v' + version)
        self.git('switch', '-q', 'main')
        return release

    def test_selects_version_only_release_and_rejects_unrelated_or_future_tags(self):
        valid = self.release('0.173.0-alpha.1', 2)
        self.release('0.174.0', 3, change_code=True)
        self.release('0.175.0', 5)
        (self.repo / 'actual-code').write_text('next source revision')
        snapshot = self.commit('source snapshot', 4)
        self.assertEqual(resolve(self.repo, snapshot).splitlines(), ['0.173.0-alpha.1', snapshot, valid, self.base])

    def test_no_compatible_release_fails(self):
        self.release('0.174.0', 2, change_code=True)
        (self.repo / 'actual-code').write_text('next source revision')
        snapshot = self.commit('source snapshot', 3)
        with self.assertRaises(RuntimeError):
            resolve(self.repo, snapshot)

    def test_stamp_uses_verified_version_and_preserves_other_fields(self):
        self.manifest.write_text('[workspace.package]\nversion = "0.0.0"\nedition = "2024"\n[workspace.dependencies]\nfoo = { version = "0.0.0" }\n')
        stamp_workspace(self.manifest, "0.174.0-alpha.5")
        self.assertIn('version = "0.174.0-alpha.5"', self.manifest.read_text())
        self.assertIn('foo = { version = "0.0.0" }', self.manifest.read_text())
        saved = self.manifest.read_text()
        stamp_workspace(self.manifest, "0.174.0-alpha.5")
        self.assertEqual(saved, self.manifest.read_text())
        with self.assertRaises(ValueError):
            stamp_workspace(self.manifest, "0.175.0")
        self.assertEqual(saved, self.manifest.read_text())

    def test_lock_validation(self):
        marker = self.repo / 'CODEX_UPSTREAM_COMMIT'
        lock = self.repo / 'release.txt'
        marker.write_text(self.base + '\n')
        lock.write_text('0.173.0-alpha.1\n' + (self.base + '\n') * 3)
        self.assertEqual(validate(lock, marker), '0.173.0-alpha.1')
        marker.write_text('f' * 40 + '\n')
        with self.assertRaises(ValueError):
            validate(lock, marker)
        marker.write_text(self.base + '\n')
        lock.write_text('0.0.0\n' + (self.base + '\n') * 3)
        with self.assertRaises(ValueError):
            validate(lock, marker)


if __name__ == '__main__':
    unittest.main()
