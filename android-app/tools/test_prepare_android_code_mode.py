#!/usr/bin/env python3
"""Regression tests for fail-closed, repeatable upstream Android adaptation."""
import unittest

from prepare_android_code_mode import (
    ANDROID_DISCOVERY,
    DISCOVERY_ANCHOR,
    HOST_CLI_ADDITION,
    HOST_CLI_ANCHOR,
    HOST_MAIN_ADDITION,
    HOST_MAIN_ANCHOR,
    adapt_discovery,
    adapt_host,
)


class AndroidCodeModeAdaptationTests(unittest.TestCase):
    def test_host_path_is_android_specific_and_idempotent(self):
        source = "fn locate() {\n" + DISCOVERY_ANCHOR + "\n}\n"
        adapted = adapt_discovery(source)
        self.assertIn('#[cfg(target_os = "android")]', adapted)
        self.assertIn('libcodex_code_mode_host.so', adapted)
        self.assertIn(ANDROID_DISCOVERY, adapted)
        self.assertEqual(adapt_discovery(adapted), adapted)

    def test_real_v8_probe_is_android_only_and_idempotent(self):
        source = ("struct Cli {\n" + HOST_CLI_ANCHOR + "\n\n"
                  + "fn main() {\n" + HOST_MAIN_ANCHOR + "\n}\n")
        adapted = adapt_host(source)
        self.assertIn(HOST_CLI_ADDITION, adapted)
        self.assertIn(HOST_MAIN_ADDITION, adapted)
        self.assertIn("initialize_v8", adapted)
        self.assertEqual(adapt_host(adapted), adapted)

    def test_outdated_upstream_causes_explicit_failure(self):
        for adapter in (adapt_discovery, adapt_host):
            with self.assertRaisesRegex(ValueError, "upstream changed"):
                adapter("upstream signature changed")

    def test_ambiguous_upstream_causes_explicit_failure(self):
        with self.assertRaisesRegex(ValueError, "upstream changed"):
            adapt_discovery(DISCOVERY_ANCHOR + "\n" + DISCOVERY_ANCHOR)


if __name__ == "__main__":
    unittest.main()
