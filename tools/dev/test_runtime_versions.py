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
                for abi in ("arm64-v8a", "x86_64"):
                    target = root / abi
                    self.fixture(target, abi, rg=version)
                    with self.assertRaisesRegex(ValueError, "expected 16.0.0, actual"):
                        m.verify_versions(target, abi)

class TermuxPackageLockTests(unittest.TestCase):
    def test_reports_all_signed_package_changes_and_rejects_them(self):
        from unittest.mock import patch
        path = p.with_name("termux_package_lock.py")
        spec = importlib.util.spec_from_file_location("termux_package_lock", path)
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        locked = {"selection": {"x86_64": {"node": ["a", "b"]}},
                  "packages": {"x86_64": {
                      name: {"version": "1", "file": f"pool/{name}.deb", "sha256": "a"*64}
                      for name in ("a", "b")}}}
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            lock = root / "lock.json"
            lock.write_text(json.dumps(locked))
            index = root / "Packages"
            index.write_text("Package: a\nVersion: 2\nFilename: pool/a.deb\nSHA256: "
                             + "b"*64 + "\n\n")
            with patch.object(verifier, "LOCK", lock):
                with self.assertRaises(ValueError) as caught:
                    verifier.verify("node", "x86_64", index, ["a", "b"])
                report = json.loads(str(caught.exception).split("changed: ", 1)[1])
                self.assertEqual(report["a"]["signedIndex"]["version"], "2")
                self.assertIsNone(report["b"]["signedIndex"])
                self.assertEqual(report["b"]["locked"]["version"], "1")

if __name__ == "__main__":
    unittest.main()
