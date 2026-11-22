#!/usr/bin/env python3
"""Screenshot discovery must accept new safe files but ignore foreign device data."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from collect_ui_screenshots import parse_discovered_screens, REQUIRED_SCREENS


class ScreenshotDiscoveryTest(unittest.TestCase):
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

    def test_large_font_rtl_evidence_is_mandatory(self):
        self.assertIn("audit-rtl-200font-bulk", REQUIRED_SCREENS)


if __name__ == "__main__":
    unittest.main()
