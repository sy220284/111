import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

class ConsolidationTests(unittest.TestCase):
    def test_user_distribution_is_manual_and_separate(self):
        dev=(ROOT/".github/workflows/dev-toolchain.yml").read_text()
        self.assertIn("workflow_dispatch:",dev)
        self.assertNotIn("  push:",dev)
        for p in ("ci.yml","release.yml","build-native-codex.yml"):
            source=(ROOT/".github/workflows"/p).read_text()
            for forbidden in ("prepare-host-toolchain","prepare-aux-toolchain","install-host-toolchain","check-ci-toolchain.sh","111-toolchain-","host-android","host-rust","host-llvm","host-emulator","host-wheelhouse","host-patcher"):
                self.assertNotIn(forbidden,source,p)
    def test_standard_environment_uses_only_official_sources(self):
        sdk=(ROOT/".github/scripts/ci-android-sdk.sh").read_text()
        self.assertIn('if [ ! -s "$SDK/$2" ]',sdk)
        self.assertIn('--install "${packages[@]}"',sdk)
        self.assertNotIn("gh api",sdk)
        action=(ROOT/".github/actions/ci-android/action.yml").read_text()
        self.assertIn("actions/setup-java@",action)
        self.assertIn("gradle/actions/setup-gradle@",action)
        self.assertNotIn("download-artifact",action)
        self.assertIn("JAVA_HOME_17_X64",action)
        self.assertIn("steps.java.outputs.present == 'false'",action)
        self.assertNotIn("system-images",sdk)
        emulator=(ROOT/".github/actions/ci-emulator/action.yml").read_text()
        self.assertIn("actions/cache/restore@",emulator)
        self.assertIn("actions/cache/save@",emulator)
        self.assertIn("steps.image.outputs.path",emulator)
        image=(ROOT/".github/scripts/ci-emulator-sdk.sh").read_text()
        self.assertIn('system-images/$IMAGE',image)
        self.assertNotIn("toolchain-lock.json",image)
        self.assertNotIn("download-artifact",emulator)
        self.assertNotIn("toolchain-lock.json",(ROOT/"tools/dev/run-emulator.sh").read_text())
        self.assertNotIn("toolchain-lock.json",(ROOT/"android-app/tools/device_matrix.py").read_text())
        ci=(ROOT/".github/workflows/ci.yml").read_text()
        self.assertEqual(2,ci.count("uses: ./.github/actions/ci-emulator"))
        self.assertNotIn("mode: build-emulator",ci)
        self.assertEqual(3,ci.count("path: ~/.cache/pip"))
        runtime=(ROOT/".github/workflows/prepare-developer-runtime.yml").read_text()
        self.assertNotIn("sdkmanager",runtime)
        self.assertIn("mode: native",runtime)

    def test_product_runtime_does_not_install_user_toolchain(self):
        for path in ("tools/runtime/build-111-runtime.sh", "tools/runtime/fetch-arm64-packages.py", "tools/runtime/runtime_versions.py", "android-app/tools/prepare_prebuilt_developer_runtime.py"):
            source = (ROOT / path).read_text()
            self.assertNotIn("tools/dev/ai-toolchain.py", source, path)
            self.assertNotIn("tools/dev/toolchain-lock.json", source, path)
        self.assertIn("fetch-arm64-packages.py", (ROOT / "tools/runtime/build-111-runtime.sh").read_text())
        import json
        user = json.loads((ROOT / "tools/dev/toolchain-lock.json").read_text())
        product = json.loads((ROOT / "tools/runtime/runtime-lock.json").read_text())
        self.assertNotIn("runtime-cache", user["shared"])
        self.assertEqual("arm64-v8a", product["packageArchive"]["version"])
