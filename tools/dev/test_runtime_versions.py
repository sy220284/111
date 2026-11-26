import importlib.util
import json
import pathlib
import tempfile
import unittest

p = pathlib.Path(__file__).resolve().parents[1] / "runtime/runtime_versions.py"
spec = importlib.util.spec_from_file_location("runtime_versions", p)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class RuntimeVersionTests(unittest.TestCase):
    def fixture(self, root, abi, rg=None):
        versions = m.versions_for(abi)
        for group, package, key in m.PACKAGES:
            target = root / group / "assets/runtime" / group / abi / "packages.tsv"
            target.parent.mkdir(parents=True, exist_ok=True)
            digest = m.LOCK["runtime777"]["gitPackageSha256"] if package == "git" else "a"*64
            with target.open("a") as output:
                output.write(f"{package}\t{rg if key == 'ripgrep' and rg else versions[key]}\tpool/{package}.deb\t{digest}\n")
        npm = root / "node/assets/runtime/node/npm/package/package.json"
        npm.parent.mkdir(parents=True)
        npm.write_text(json.dumps({"version":versions["npm"]}))

    def test_each_abi_exact_version(self):
        for abi in ("arm64-v8a", "x86_64"):
            with self.subTest(abi=abi), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                self.fixture(root, abi)
                self.assertEqual(m.verify_versions(root, abi)["git"]["ripgrep"]["version"], m.versions_for(abi)["ripgrep"])

    def test_cross_abi_and_unreviewed_drift_rejected(self):
        for version in ("15.2.0", "16.0.1", "16.0.0-1"):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                self.fixture(root, "x86_64", rg=version)
                with self.assertRaisesRegex(ValueError, "expected 16.0.0, actual"):
                    m.verify_versions(root, "x86_64")

if __name__ == "__main__":
    unittest.main()
