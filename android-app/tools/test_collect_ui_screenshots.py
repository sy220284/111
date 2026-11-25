#!/usr/bin/env python3
"""Screenshot discovery must accept new safe files but ignore foreign device data."""
import sys
import tempfile
import unittest
import re
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_ui_screenshots import parse_discovered_screens, REQUIRED_SCREENS, valid_png, required_screens, duplicate_content_groups


class ScreenshotDiscoveryTest(unittest.TestCase):
    def test_full_display_evidence_cannot_bypass_shared_capture(self):
        source = Path(__file__).resolve().parents[1] / "app/src/androidTest/java"
        files = list(source.rglob("*.kt"))
        self.assertTrue(files, "必须检查实际设备测试源码")
        bypasses = []
        for path in files:
            code = path.read_text(encoding="utf-8")
            if path.name != "VerifiedScreenshotCapture.kt" and re.search(r"\.takeScreenshot\s*\(", code):
                bypasses.append(str(path.relative_to(source)))
            if re.search(r"\.onRoot\s*\([^)]*\)\s*\.captureToImage\s*\(", code):
                bypasses.append(str(path.relative_to(source)))
        self.assertEqual([], bypasses, "整屏证据统一校验窗口归属与系统浮层；图片像素断言仍可使用组件采样")

    def test_every_route_must_register_distinct_device_evidence(self):
        manifest = {"routes": [{"route": "chat", "screens": ["chat"]}],
                    "requiredStateScreens": ["keyboard"], "optionalStateScreens": []}
        self.assertEqual(["chat", "keyboard"], required_screens(manifest))
        manifest["routes"].append({"route": "settings", "screens": []})
        with self.assertRaises(ValueError):
            required_screens(manifest)
        manifest["routes"][-1]["screens"] = ["chat"]
        with self.assertRaises(ValueError):
            required_screens(manifest)

    def test_corrupt_or_empty_png_does_not_count_as_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "capture.png"
            self.assertFalse(valid_png(path))
            path.write_bytes(b"not a png")
            self.assertFalse(valid_png(path))
            # Build a real PNG with checksummed chunks rather than relying on header bytes.
            import struct
            import zlib
            def chunk(kind, data):
                return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
            valid = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\0\0\0\0")) + chunk(b"IEND", b"")
            path.write_bytes(valid)
            self.assertTrue(valid_png(path))
            path.write_bytes(valid[:-5])
            self.assertFalse(valid_png(path))
            path.write_bytes(valid[:35] + bytes([valid[35] ^ 1]) + valid[36:])
            self.assertFalse(valid_png(path))

    def test_duplicate_content_is_reported_across_distinct_names(self):
        with tempfile.TemporaryDirectory() as folder:
            files = [Path(folder) / name for name in ("a.png", "b.png", "c.png")]
            for path, content in zip(files, (b"same", b"other", b"same")):
                path.write_bytes(content)
            self.assertEqual([["a.png", "c.png"]], duplicate_content_groups(files))

    def test_discovers_current_and_future_ui_screenshots(self):
        output = "home-android35.png\r\naudit-rtl-200font-bulk-android35.png\nnew-component-android35.png\n"
        self.assertEqual(
            ["audit-rtl-200font-bulk", "home", "new-component"],
            parse_discovered_screens(output),
        )

    def test_rejects_traversals_other_extensions_and_noise(self):
        output = "../outside-android35.png\nmalicious/side-android35.png\n"
        output += "foo.txt\nalert.png\naudit-rtl-200font-bulk-android35.png\n"
        self.assertEqual(
            ["audit-rtl-200font-bulk"], parse_discovered_screens(output)
        )

    def test_project_terminal_sheet_replaces_removed_global_tool_menu(self):
        self.assertIn("terminal-sheet", REQUIRED_SCREENS)
        self.assertNotIn("tools-sheet", REQUIRED_SCREENS)
        self.assertEqual(len(REQUIRED_SCREENS), len(set(REQUIRED_SCREENS)))

    def test_large_font_rtl_evidence_is_mandatory(self):
        self.assertIn("audit-rtl-200font-bulk", REQUIRED_SCREENS)

    def test_mcp_only_usable_services_evidence_is_mandatory(self):
        for screenshot in (
            "audit-mcp-usable-directory",
            "audit-mcp-chatgpt-available",
            "audit-mcp-service-boundary",
        ):
            self.assertIn(screenshot, REQUIRED_SCREENS)

    def test_offscreen_work_protection_evidence_is_mandatory(self):
        self.assertIn("audit-backup-background-block", REQUIRED_SCREENS)
        self.assertIn("audit-backup-in-progress", REQUIRED_SCREENS)


if __name__ == "__main__":
    unittest.main()
