"""Regression tests for the APK executable and size safety gate."""
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from audit_apk_contents import ApkAuditError, REQUIRED_RUNTIME, audit


def elf(machine):
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header)


class ApkAuditTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.path = Path(self.work.name) / "app.apk"

    def package(self, *, core=True, wrong_abi=False, malformed=False,
                missing_component=None, wrong_machine_component=None,
                abi="arm64-v8a"):
        payload = {
            "AndroidManifest.xml": b"manifest",
            "resources.arsc": b"resources",
            "classes.dex": b"dex",
            f"lib/{abi}/libhpatchz.so": elf(183 if abi == "arm64-v8a" else 62),
        }
        if core:
            for ext in ("zip", "sha256", "version"):
                payload["assets/development-runtime/development-runtime." + ext] = b"fixture"
            for component in REQUIRED_RUNTIME:
                if component == missing_component:
                    continue
                machine = 62 if component == wrong_machine_component else (
                    183 if abi == "arm64-v8a" else 62)
                payload[f"lib/{abi}/{component}"] = elf(machine)
        if wrong_abi:
            payload["lib/x86_64/libunexpected.so"] = elf(62)
        if malformed:
            payload[f"lib/{abi}/libhpatchz.so"] = b"fake"
        with ZipFile(self.path, "w") as dest:
            for name, data in payload.items():
                dest.writestr(name, data)

    def test_full_core_and_size_breakdown(self):
        self.package()
        result = audit(self.path, "arm64-v8a", "full")
        self.assertEqual(64, result["core_bytes"])
        self.assertEqual(64 * len(REQUIRED_RUNTIME), result["runtime_bytes"])
        self.assertGreater(result["uncompressed_by_category"]["native"], 100)
        self.assertEqual(result["apk_bytes"], self.path.stat().st_size)

    def test_ui_smoke_has_no_core_but_full_release_requires_it(self):
        self.package(core=False)
        self.assertIsNone(audit(self.path, "arm64-v8a", "ui-smoke")["core_bytes"])
        with self.assertRaisesRegex(ApkAuditError, "libcodex_server"):
            audit(self.path, "arm64-v8a", "full")

    def test_rejects_wrong_abi_and_malformed_elf(self):
        self.package(wrong_abi=True)
        with self.assertRaisesRegex(ApkAuditError, "非目标 ABI"):
            audit(self.path, "arm64-v8a", "full")
        self.package(malformed=True)
        with self.assertRaisesRegex(ApkAuditError, "ELF64"):
            audit(self.path, "arm64-v8a", "full")

    def test_full_package_requires_each_codex_component(self):
        for component in REQUIRED_RUNTIME:
            with self.subTest(component=component):
                self.package(missing_component=component)
                with self.assertRaisesRegex(ApkAuditError, component):
                    audit(self.path, "arm64-v8a", "full")

    def test_rejects_wrong_architecture_for_code_mode_host(self):
        self.package(wrong_machine_component="libcodex_code_mode_host.so")
        with self.assertRaisesRegex(ApkAuditError, "原生库架构错误"):
            audit(self.path, "arm64-v8a", "full")

    def test_x86_runtime_is_valid_only_in_emulator_package(self):
        self.package(abi="x86_64")
        self.assertEqual("x86_64", audit(self.path, "x86_64", "full")["abi"])
        with self.assertRaises(ApkAuditError):
            audit(self.path, "arm64-v8a", "full")

    def test_rejects_missing_android_manifest(self):
        with ZipFile(self.path, "w") as archive:
            archive.writestr("classes.dex", b"dex")
        with self.assertRaisesRegex(ApkAuditError, "AndroidManifest"):
            audit(self.path, "arm64-v8a", "ui-smoke")


if __name__ == "__main__":
    unittest.main()
