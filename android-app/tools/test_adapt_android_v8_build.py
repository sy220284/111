#!/usr/bin/env python3
"""Regression checks for the pinned rusty_v8 Android host sysroot adaptation."""
import unittest

from adapt_android_v8_build import OLD, OLD_SYSROOT, NEW, NEW_SYSROOT, adapt


class AndroidV8BuildTests(unittest.TestCase):
    def test_android_x86_host_uses_runner_linux_and_ndk_target(self):
        src = "fn build() {\n" + OLD + "\n" + OLD_SYSROOT + "\n}\n"
        result = adapt(src)
        self.assertIn(NEW, result)
        self.assertIn(NEW_SYSROOT, result)
        self.assertNotIn(OLD_SYSROOT, result)
        self.assertEqual(adapt(result), result)

    def test_upstream_mismatch_requires_review(self):
        with self.assertRaisesRegex(ValueError, "Upstream rusty_v8 changed"):
            adapt("fn build() {}")

    def test_duplicate_anchor_fails(self):
        src = OLD + "\n" + OLD + "\n" + OLD_SYSROOT
        with self.assertRaisesRegex(ValueError, "Upstream rusty_v8 changed"):
            adapt(src)


if __name__ == "__main__":
    unittest.main()
