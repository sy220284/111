#!/usr/bin/env python3
"""Regression checks for the pinned rusty_v8 Android host sysroot adaptation."""
import unittest

from adapt_android_v8_build import OLD, OLD_SYSROOT, OLD_BINDGEN, NEW, NEW_SYSROOT, NEW_BINDGEN, adapt


class AndroidV8BuildTests(unittest.TestCase):
    def test_android_x86_host_uses_runner_linux_and_ndk_target(self):
        src = "fn build() {\n" + OLD + "\n" + OLD_SYSROOT + "\n" + OLD_BINDGEN + "\n}\n"
        result = adapt(src)
        self.assertIn(NEW, result)
        self.assertIn(NEW_SYSROOT, result)
        self.assertIn(NEW_BINDGEN, result)
        self.assertLess(NEW_BINDGEN.index(".clang_args(filtered_args)"), NEW_BINDGEN.index(".clang_args(android_args)"))
        self.assertNotIn(OLD_SYSROOT, result)
        self.assertEqual(adapt(result), result)

    def test_pinned_rusty_v8_android_block(self):
        source = """
  if target_triple != env::var("HOST").unwrap() && target_os == "android" {
    if target_arch == "x86_64" {
      maybe_install_sysroot("amd64");
    }
    gn_args.push(format!(r#"v8_target_cpu="{arch}""#).to_string());
    gn_args.push("use_sysroot=true".to_string());

    // NDK 23 and above removes libgcc entirely.
  }
"""
        changed = adapt(source + OLD_BINDGEN)
        self.assertNotIn("maybe_install_sysroot(\"amd64\")", changed)
        self.assertIn('use_sysroot=false', changed)
        self.assertEqual(changed, adapt(changed))

    def test_upstream_mismatch_requires_review(self):
        with self.assertRaisesRegex(ValueError, "Upstream rusty_v8 changed"):
            adapt("fn build() {}")

    def test_duplicate_anchor_fails(self):
        src = OLD + "\n" + OLD + "\n" + OLD_SYSROOT + "\n" + OLD_BINDGEN
        with self.assertRaisesRegex(ValueError, "Upstream rusty_v8 changed"):
            adapt(src)


if __name__ == "__main__":
    unittest.main()
