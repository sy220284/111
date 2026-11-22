"""Regression tests for the APK executable and size safety gate."""
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from audit_apk_contents import ApkAuditError, audit


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

    def package(self, *, core=True, wrong_abi=False, malformed=False, abi="arm64-v8a"):
        payload = {
            "AndroidManifest.xml": b"manifest",
            "resources.arsc": b"resources",
            "classes.dex": b"dex",
            f"lib/{abi}/libhpatchz.so": elf(183 if abi == "arm64-v8a" else 62),
        }
        if core:
            payload[f"lib/{abi}/libcodex_server.so"] = elf(
                183 if abi == "arm64-v8a" else 62)
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
