import contextlib
import io
from pathlib import Path
import shutil
import tempfile
import unittest

from merge_codex_upstream import MARKER, ORIGIN, PREFIX, UpgradeError, git, merge, output


class UpstreamMergeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        git(self.repo, 'init', '-q')
        self.write('shared.txt', 'first\nsecond\nthird\nfourth\nfifth\n')
        self.write('obsolete.txt', 'old\n')
        self.write('rename.txt', 'rename content\n')
        self.write('binary.dat', b'\0old')
        self.write('mode.sh', '#!/bin/sh\n')
        self.write('local-delete.txt', 'remove locally\n')
        self.base = self.commit('base')
        git(self.repo, 'switch', '-c', 'official')
        self.write('shared.txt', 'first\nsecond\nthird\nfourth\nUPSTREAM\n')
        (self.repo / 'obsolete.txt').unlink()
        (self.repo / 'rename.txt').rename(self.repo / 'renamed.txt')
        self.write('binary.dat', b'\0new')
        (self.repo / 'mode.sh').chmod(0o755)
        self.write('new-official.txt', 'new\n')
        self.target = self.commit('upstream')
        git(self.repo, 'switch', '--orphan', 'app')
        git(self.repo, 'checkout', self.base, '--', '.')
        directory = self.repo / PREFIX
        directory.mkdir(parents=True)
        for p in list(self.repo.iterdir()):
            if p.name not in ('.git', 'upstream'):
                shutil.move(str(p), directory / p.name)
        self.write(MARKER, self.base + '\n2026-01-01T00:00:00Z\n' + ORIGIN + '\n')
        self.write('android.txt', 'outside\n')
        self.commit('app snapshot')

    def write(self, path, value):
        p = self.repo / path
        p.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(value, bytes):
            p.write_bytes(value)
        else:
            p.write_text(value, encoding='utf-8')

    def commit(self, message):
        git(self.repo, 'add', '-A')
        git(self.repo, 'commit', '-qm', message)
        return output(self.repo, 'rev-parse', 'HEAD')

    def local(self, path, value):
        self.write(PREFIX + '/' + path, value)

    def read(self, path):
        return (self.repo / PREFIX / path).read_text()

    def run_merge(self, target=None):
        with contextlib.redirect_stdout(io.StringIO()):
            merge(self.repo, target or self.target)

    def assert_clean_failure(self, target=None):
        marker = (self.repo / MARKER).read_bytes()
        tree = output(self.repo, 'write-tree')
        with self.assertRaises(UpgradeError):
            self.run_merge(target)
        self.assertEqual(output(self.repo, 'write-tree'), tree)
        self.assertEqual((self.repo / MARKER).read_bytes(), marker)
        self.assertEqual(output(self.repo, 'status', '--porcelain'), '')

    def test_merge_and_preserve_local_delta(self):
        self.local('shared.txt', 'LOCAL\nsecond\nthird\nfourth\nfifth\n')
        self.local('custom.txt', 'custom\n')
        self.local('rename.txt', 'rename content\nlocal extension\n')
        (self.repo / PREFIX / 'local-delete.txt').unlink()
        self.commit('local customizations')
        self.run_merge()
        self.assertEqual(self.read('shared.txt'), 'LOCAL\nsecond\nthird\nfourth\nUPSTREAM\n')
        self.assertEqual(self.read('custom.txt'), 'custom\n')
        self.assertEqual(self.read('renamed.txt'), 'rename content\nlocal extension\n')
        for name in ('obsolete.txt', 'rename.txt', 'local-delete.txt'):
            self.assertFalse((self.repo / PREFIX / name).exists())
        self.assertEqual((self.repo / PREFIX / 'binary.dat').read_bytes(), b'\0new')
        self.assertTrue((self.repo / PREFIX / 'mode.sh').stat().st_mode & 0o111)
        self.assertEqual(self.read('new-official.txt'), 'new\n')
        self.assertEqual((self.repo / 'android.txt').read_text(), 'outside\n')
        self.assertEqual((self.repo / MARKER).read_text().splitlines()[0], self.target)
        self.commit('upgrade')
        self.run_merge()
        self.assertEqual(output(self.repo, 'status', '--porcelain'), '')

    def test_same_line_conflict_is_atomic(self):
        self.local('shared.txt', 'first\nsecond\nthird\nfourth\nLOCAL\n')
        self.commit('conflict')
        self.assert_clean_failure()

    def test_delete_modify_conflict_is_atomic(self):
        self.local('obsolete.txt', 'local update\n')
        self.commit('conflict')
        self.assert_clean_failure()

    def test_add_add_conflict_is_atomic(self):
        self.local('new-official.txt', 'local new\n')
        self.commit('conflict')
        self.assert_clean_failure()

    def test_non_descendant_rejected(self):
        self.assert_clean_failure(output(self.repo, 'rev-parse', 'HEAD'))

    def test_dirty_checkout_rejected(self):
        self.local('custom.txt', 'uncommitted\n')
        with self.assertRaises(UpgradeError):
            self.run_merge()
        self.assertEqual(self.read('custom.txt'), 'uncommitted\n')
        self.assertEqual((self.repo / MARKER).read_text().splitlines()[0], self.base)

    def test_upstream_absorbs_same_local_change(self):
        self.local('shared.txt', 'first\nsecond\nthird\nfourth\nUPSTREAM\n')
        self.commit('same change')
        self.run_merge()
        self.assertEqual(self.read('shared.txt'), 'first\nsecond\nthird\nfourth\nUPSTREAM\n')


if __name__ == '__main__':
    unittest.main()
