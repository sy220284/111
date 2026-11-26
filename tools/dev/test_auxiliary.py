import pathlib
import tempfile
import unittest

import auxiliary


class PortableAuxiliaryTest(unittest.TestCase):
    def test_pnpm_checkout_registry_removed_without_touching_packages(self):
        with tempfile.TemporaryDirectory() as root:
            root=pathlib.Path(root)
            store=root/"store"; version=store/"v10"; index=version/"projects"
            index.mkdir(parents=True)
            target=root/"checkout"; target.mkdir()
            (target/"keep").write_text("source")
            (index/"checkout").symlink_to(target, target_is_directory=True)
            (version/"files").mkdir()
            (version/"files"/"package").write_text("package")
            auxiliary.remove_pnpm_project_index(store)
            self.assertFalse(index.exists())
            self.assertEqual((target/"keep").read_text(), "source")
            self.assertEqual((version/"files"/"package").read_text(), "package")
            auxiliary.remove_pnpm_project_index(store)

    def test_registry_snapshot_is_portable_and_rejects_external_links(self):
        with tempfile.TemporaryDirectory() as root:
            root=pathlib.Path(root)
            source=root/"output"/"hash"/"external"/auxiliary.BAZEL_REGISTRY_REPO
            source.mkdir(parents=True)
            (source/"config.json").write_text('{"dl":"https://example.invalid"}')
            destination=root/"stage"
            auxiliary.bazel_registry_snapshot(root/"output",destination)
            self.assertEqual((destination/"config.json").read_bytes(),(source/"config.json").read_bytes())
            (source/"external").symlink_to(root)
            with self.assertRaises(ValueError):
                auxiliary.bazel_registry_snapshot(root/"output",root/"bad")


if __name__ == "__main__":
    unittest.main()
