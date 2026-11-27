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

    def test_all_registry_snapshots_are_required_for_disconnected_fetch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            dest=root/"stage"; dest.mkdir()
            external=root/"output"/"hash"/"external"
            future="rules_rs++crate+future_workspace_https___index.crates.io_"
            expected=sorted((*auxiliary.BAZEL_REGISTRY_REPOS,future))
            for name in expected:
                source=external/name
                source.mkdir(parents=True)
                (source/"config.json").write_text(name)
            (external/"rules_rs++crate+crates__heck-0.5.0").mkdir()
            names=auxiliary.bazel_registry_names(external)
            self.assertEqual(names,expected)
            for name in names:
                auxiliary.bazel_registry_snapshot(root/"output",dest/name,name)
            (dest/"repositories.json").write_text(auxiliary.json.dumps(names))
            self.assertEqual(len(list(auxiliary.bazel_registry_overrides(dest))),len(expected))
            for name in expected:
                self.assertEqual((dest/name/"config.json").read_text(),name)
            (dest/future/"config.json").unlink()
            (dest/future).rmdir()
            with self.assertRaisesRegex(ValueError,"incomplete Bazel registry set"):
                list(auxiliary.bazel_registry_overrides(dest))

    def test_git_repository_snapshots_preserve_internal_links_and_materialize_build_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            workspace=root/"workspace"; workspace.mkdir()
            (workspace/"MODULE.bazel").write_text('git_repository(\n    name = "direct_git",\n)')
            (workspace/"BUILD.bazel").write_text("exports_files([])")
            module=workspace/"MODULE.bazel"
            module.write_text(module.read_text()+'\nuse_extension("//third_party/v8:dependencies.bzl", "dependencies")\n')
            extension=workspace/"third_party/v8/dependencies.bzl"
            extension.parent.mkdir(parents=True)
            extension.write_text('def _dependencies_impl(ctx):\n    git_repository(\n        name = "v8_icu",\n        commit = "d578f2e8b7bd5938e21cfb6bf15c079e0aa5b738",\n    )\n    http_archive(\n        name = "not_git",\n    )\n')
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
            ext=external/"+dependencies+v8_icu"; ext.mkdir()
            (ext/"file").write_text("icu")
            (ext/"BUILD.bazel").symlink_to(workspace/"BUILD.bazel")
            (external/"+dependencies+not_git").mkdir()
            dest=root/"stage"
            with patch.object(auxiliary,"UPSTREAM",workspace):
                auxiliary.bazel_git_snapshots(external,dest,root)
                self.assertEqual((dest/name/"internal").read_text(),"locked")
                self.assertEqual((dest/name/"internal").readlink(),pathlib.Path("data"))
                self.assertFalse((dest/name/"BUILD.bazel").is_symlink())
                self.assertEqual((dest/name/"BUILD.bazel").read_text(),"exports_files([])")
                self.assertEqual(len(list(auxiliary.bazel_git_overrides(dest))),3)
                self.assertEqual((dest/ext.name/"file").read_text(),"icu")
                self.assertFalse((dest/ext.name/"BUILD.bazel").is_symlink())
                self.assertFalse((dest/"+dependencies+not_git").exists())
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

    def test_pip_repositories_survive_relocation_without_producer_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            workspace=root/"workspace"; workspace.mkdir()
            (workspace/"MODULE.bazel").write_text('pip.parse(hub_name = "v8_python_deps")\npip.parse(hub_name = "schema_python")')
            output=root/"producer"; external=output/"hash/external"; external.mkdir(parents=True)
            names=["rules_python++pip+v8_python_deps_311_jinja2", "rules_python++pip+v8_python_deps_311_markupsafe",
                   "rules_python++pip+schema_python_312_pydantic"]
            for name in names:
                source=output/"repo-contents-cache"/name; source.mkdir(parents=True)
                (source/"BUILD.bazel").write_text('py_library(name = "pkg")')
                (source/"package.py").write_text(name)
                (source/"alias.py").symlink_to(source/"package.py")
                (external/name).symlink_to(source,target_is_directory=True)
                (external/(name+".marker")).write_text("marker")
            (external/"rules_python++pip+v8_python_deps").mkdir()
            (external/"rules_python++config+pypi__wheel").mkdir()
            stage=root/"stage"; stage.mkdir()
            dest=stage/"pip-repositories"
            with patch.object(auxiliary,"UPSTREAM",workspace):
                auxiliary.bazel_pip_snapshots(external,dest,output)
                auxiliary.shutil.rmtree(output)
                moved=root/"consumer"
                stage.rename(moved)
                restored=moved/"pip-repositories"
                overrides=list(auxiliary.bazel_pip_overrides(restored))
                self.assertEqual(len(overrides),len(names))
                for name in names:
                    self.assertIn("--override_repository="+name+"="+str(restored/name),overrides)
                    self.assertEqual((restored/name/"alias.py").read_text(),name)
                    self.assertEqual((restored/name/"alias.py").readlink(),pathlib.Path("package.py"))
                auxiliary.shutil.rmtree(restored/names[0])
                with self.assertRaises(ValueError):
                    list(auxiliary.bazel_pip_overrides(restored))

    def test_pip_snapshots_reject_external_links_and_unregistered_repositories(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            workspace=root/"workspace"; workspace.mkdir()
            (workspace/"MODULE.bazel").write_text('pip.parse(hub_name = "v8_python_deps")')
            external=root/"output/external"; external.mkdir(parents=True)
            source=external/"rules_python++pip+v8_python_deps_311_jinja2"; source.mkdir()
            (source/"BUILD.bazel").write_text('exports_files([])')
            (source/"escape").symlink_to(workspace,target_is_directory=True)
            with patch.object(auxiliary,"UPSTREAM",workspace):
                with self.assertRaisesRegex(ValueError,"non-portable repository link"):
                    auxiliary.bazel_pip_snapshots(external,root/"bad",root/"output")
                (source/"escape").unlink()
                dest=root/"snapshot"
                auxiliary.bazel_pip_snapshots(external,dest,root/"output")
                (dest/"rules_python++pip+unregistered_311_package").mkdir()
                with self.assertRaises(ValueError):
                    list(auxiliary.bazel_pip_overrides(dest))

    def test_disconnected_bazel_fetch_consumes_pip_snapshots_and_keeps_download_ban(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage=pathlib.Path(tmp)
            with patch.object(auxiliary,"output",return_value="bazel "+auxiliary.LOCK["bazel"]), \
                 patch.object(auxiliary,"bazel_registry_overrides",return_value=["--override_repository=registry=local"]), \
                 patch.object(auxiliary,"bazel_git_overrides",return_value=["--override_repository=git=local"]), \
                 patch.object(auxiliary,"bazel_pip_overrides",return_value=["--override_repository=pip=local"]) as pip, \
                 patch.object(auxiliary,"run") as run:
                auxiliary.bazel_fetch(stage,True)
                pip.assert_called_once_with(stage/"pip-repositories")
                args=run.call_args.args
                self.assertIn("--repository_disable_download",args)
                self.assertIn("--override_repository=pip=local",args)
                self.assertEqual(args[-len(auxiliary.BAZEL_TARGETS):],auxiliary.BAZEL_TARGETS)


if __name__ == "__main__":
    unittest.main()
