import pathlib
import tempfile
import unittest
from unittest.mock import patch

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

    def test_git_repository_snapshots_preserve_internal_links_and_materialize_build_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            workspace=root/"workspace"; workspace.mkdir()
            (workspace/"MODULE.bazel").write_text('git_repository(\n    name = "direct_git",\n)')
            (workspace/"BUILD.bazel").write_text("exports_files([])")
            for lock_path in ("codex-rs/Cargo.lock","tools/argument-comment-lint/Cargo.lock"):
                lock=workspace/lock_path
                lock.parent.mkdir(parents=True)
                lock.write_text('[[package]]\nname = "example"\nversion = "1.0.0"\n'
                    'source = "git+https://github.com/example/repo.git#1234567890123456789012345678901234567890"\n')
            external=root/"output"; external.mkdir()
            name="rules_rs++crate+crates__github.com_example_repo.git_12345678"
            cache=root/"repo-contents-cache"/"git-source"; cache.mkdir(parents=True)
            alias=external/name; alias.symlink_to(cache,target_is_directory=True)
            source=cache
            (external/(name+".marker")).write_text("marker")
            ordinary=root/"ordinary"; ordinary.mkdir()
            (external/"rules_rs++crate+crates__rama-http-0.3.0-alpha.4").symlink_to(ordinary,target_is_directory=True)
            (source/"data").write_text("locked")
            (source/"internal").symlink_to(source/"data")
            (source/"BUILD.bazel").symlink_to(workspace/"BUILD.bazel")
            direct=external/"+git_repository+direct_git"; direct.mkdir()
            (direct/"file").write_text("direct")
            dest=root/"stage"
            with patch.object(auxiliary,"UPSTREAM",workspace):
                auxiliary.bazel_git_snapshots(external,dest,root)
                self.assertEqual((dest/name/"internal").read_text(),"locked")
                self.assertEqual((dest/name/"internal").readlink(),pathlib.Path("data"))
                self.assertFalse((dest/name/"BUILD.bazel").is_symlink())
                self.assertEqual((dest/name/"BUILD.bazel").read_text(),"exports_files([])")
                self.assertEqual(len(list(auxiliary.bazel_git_overrides(dest))),2)
                self.assertFalse((dest/"rules_rs++crate+crates__rama-http-0.3.0-alpha.4").exists())
                (source/"escape").symlink_to(root)
                with self.assertRaises(ValueError):
                    auxiliary.bazel_git_snapshots(external,root/"bad",root)
            (dest/"extra").mkdir()
            with self.assertRaises(ValueError):
                list(auxiliary.bazel_git_overrides(dest))

    def test_pnpm_lifecycle_inherits_component_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage=pathlib.Path(tmp)
            with patch.object(auxiliary,"output",return_value=auxiliary.LOCK["pnpm"]), patch.object(auxiliary,"run") as run:
                auxiliary.pnpm_install(stage,True)
                self.assertEqual(run.call_count,2)
                for call in run.call_args_list:
                    self.assertEqual(call.kwargs["env"]["PATH"].split(auxiliary.os.pathsep)[0],str(stage/"bin"))


if __name__ == "__main__":
    unittest.main()
