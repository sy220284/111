import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

class ConsolidationTests(unittest.TestCase):
    def test_user_distribution_is_manual_and_separate(self):
        dev=(ROOT/".github/workflows/dev-toolchain.yml").read_text()
        self.assertIn("workflow_dispatch:",dev)
        self.assertNotIn("  push:",dev)
        self.assertNotIn("prepare-developer-runtime.yml",dev)
        self.assertNotIn("run_ci_script_tests.py",dev)
        for p in ("ci.yml","release.yml","build-native-codex.yml","prepare-developer-runtime.yml"):
            source=(ROOT/".github/workflows"/p).read_text()
            for forbidden in ("prepare-host-toolchain","prepare-aux-toolchain","install-host-toolchain","check-ci-toolchain.sh","111-toolchain-","host-android","host-rust","host-llvm","host-emulator","host-wheelhouse","host-patcher"):
                self.assertNotIn(forbidden,source,p)
            self.assertNotIn("tools/dev/", source, p)
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
        self.assertNotIn("toolchain-lock.json",(ROOT/"tools/build/run-emulator.sh").read_text())
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

    def test_shared_android_build_and_runtime_ownership(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        release = (ROOT / ".github/workflows/release.yml").read_text()
        android = (ROOT / ".github/actions/ci-android/action.yml").read_text()
        compile_job = ci.split("\n  verify-ui:\n", 1)[1].split("\n  ui-emulator:\n", 1)[0]
        self.assertIn("    needs: plan\n", compile_job)
        self.assertNotIn("needs.static-ui", compile_job)
        self.assertNotIn("needs.preflight", compile_job)
        self.assertIn("needs: [plan, native", ci)
        self.assertIn("static-ui, verify-ui", ci)
        self.assertNotIn("校验固定 Gradle Wrapper", ci)
        self.assertNotIn("校验固定 Gradle Wrapper", release)
        self.assertLess(android.index("校验固定 Gradle Wrapper"), android.index("gradle/actions/setup-gradle@"))
        self.assertEqual(2, ci.count("bash .github/scripts/assemble-product-runtime.sh "))
        self.assertEqual(1, release.count("bash .github/scripts/assemble-product-runtime.sh "))
        import subprocess
        subprocess.run(["bash", "-n", str(ROOT / ".github/scripts/assemble-product-runtime.sh")], check=True)
