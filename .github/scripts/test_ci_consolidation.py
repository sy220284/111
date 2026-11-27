import unittest
from pathlib import Path
from ci_policy import toolchain_changed

ROOT=Path(__file__).resolve().parents[1]
class ConsolidationTests(unittest.TestCase):
    def test_developer_workflow_is_manual_and_audit_has_one_owner(self):
        dev=(ROOT/"workflows/dev-toolchain.yml").read_text()
        ci=(ROOT/"workflows/ci.yml").read_text()
        self.assertNotIn("  push:",dev)
        self.assertEqual(1,ci.count("bash tools/dev/check-ci-toolchain.sh"))
        self.assertEqual(1,dev.count("bash tools/dev/check-ci-toolchain.sh"))
    def test_relevant_changes_only(self):
        for p in ("tools/dev/ci_host.py","android-app/buildSrc/src/main/X.kt","android-app/app/build.gradle.kts",
                  ".github/workflows/ci.yml","upstream/codex/codex-rs/core/Cargo.toml","android-app/gradle/verification-metadata.xml"):
            self.assertTrue(toolchain_changed([p]),p)
        self.assertFalse(toolchain_changed(["android-app/app/src/main/java/X.kt","docs/README.md"]))
