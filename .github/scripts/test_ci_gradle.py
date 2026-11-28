import hashlib
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/ci-gradle.sh"

class GradleResolutionTests(unittest.TestCase):
    def run_case(self, kind, trusted=True, primary_status="200", mirror_status="200"):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            app = root / "android-app"
            (app / "gradle").mkdir(parents=True)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            module = b"reviewed module"
            digest = hashlib.sha256(module if trusted else b"other").hexdigest()
            (app / "gradle/verification-metadata.xml").write_text(
                '<verification-metadata><components><component group="org.jetbrains.kotlin" name="compose-compiler-gradle-plugin" version="2.4.21"><artifact name="compose-compiler-gradle-plugin-2.4.21.module"><sha256 value="' + digest + '"/></artifact></component></components></verification-metadata>')
            gradlew = app / "gradlew"
            gradlew.write_text("""#!/usr/bin/env bash
printf '%s\\n' "$*" >> calls
if [[ "$*" == *--refresh-dependencies* || "$*" == *--init-script* ]]; then exit 0; fi
case "$TEST_KIND" in
success) exit 0 ;;
plugin) echo "Plugin [id: 'org.jetbrains.kotlin.plugin.compose', version: '2.4.21', artifact: 'org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21', apply: false] was not found" ;;
*) echo "Kotlin compilation failed" ;;
esac
exit 17
""")
            gradlew.chmod(0o755)
            curl = bin_dir / "curl"
            curl.write_text("""#!/usr/bin/env bash
url="${@: -3:1}"
case "$url" in
https://repo.maven.apache.org/*) status="$TEST_PRIMARY_STATUS" ;;
https://maven-central.storage-download.googleapis.com/*) status="$TEST_MIRROR_STATUS" ;;
*) exit 2 ;;
esac
printf '%s\\n' "$url" >> probes
printf '%s' "$status"
if [[ "$status" != 200 ]]; then exit 22; fi
printf 'reviewed module' > "${@: -1}"
""")
            curl.chmod(0o755)
            env = {**os.environ, "PATH": str(bin_dir) + ":" + os.environ["PATH"], "TEST_KIND": kind,
                   "TEST_PRIMARY_STATUS": primary_status, "TEST_MIRROR_STATUS": mirror_status}
            result = subprocess.run(["bash", str(SCRIPT), "--dependency-verification", "strict", ":app:assembleRelease"], cwd=root, env=env, text=True, capture_output=True)
            probes = (root / "probes").read_text().splitlines() if (root / "probes").exists() else []
            return result, (root / "calls").read_text().splitlines(), probes

    def test_success_no_retry(self):
        r, calls, _ = self.run_case("success")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(len(calls), 1)

    def test_only_verified_plugin_failure_reresolves(self):
        r, calls, _ = self.run_case("plugin")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(len(calls), 2)
        self.assertIn("--refresh-dependencies --info", calls[-1])
        self.assertIn("--dependency-verification strict", calls[-1])

    def test_other_failure_retains_exit_status(self):
        r, calls, _ = self.run_case("compile")
        self.assertEqual(r.returncode, 17)
        self.assertEqual(len(calls), 1)

    def test_untrusted_module_refuses_retry(self):
        r, calls, _ = self.run_case("plugin", trusted=False)
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(len(calls), 1)

    def test_rate_limit_uses_a_verified_mirror_without_refreshing_all_dependencies(self):
        r, calls, probes = self.run_case("plugin", primary_status="429")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(len(calls), 2)
        self.assertEqual(len(probes), 2)
        self.assertIn("https://maven-central.storage-download.googleapis.com/maven2/", probes[1])
        self.assertRegex(calls[1], r"--init-script /.*?/android-app/gradle/ci-maven-central\.init\.gradle")
        self.assertIn("--dependency-verification strict", calls[1])
        self.assertNotIn("--refresh-dependencies", calls[1])

    def test_rate_limited_or_mismatched_mirror_refuses_retry(self):
        for kwargs in ({"mirror_status": "429"}, {"trusted": False}):
            with self.subTest(**kwargs):
                r, calls, _ = self.run_case("plugin", primary_status="429", **kwargs)
                self.assertNotEqual(r.returncode, 0)
                self.assertEqual(len(calls), 1)

    def test_unrelated_probe_failure_keeps_original_failure(self):
        r, calls, probes = self.run_case("plugin", primary_status="404")
        self.assertEqual(r.returncode, 17)
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(probes), 1)

    def test_shared_ci_entry_and_real_candidates(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        release = (ROOT / ".github/workflows/release.yml").read_text()
        for workflow in (ci, release):
            self.assertNotIn("./android-app/gradlew -p android-app", workflow)
            self.assertIn("ci-gradle.sh -p android-app --dependency-verification strict", workflow)
        runtime = ci.split("\n  runtime-test:\n", 1)[1].split("\n  verify-apk:\n", 1)[0]
        self.assertIn("-PcodexAndroid17Candidate=true", runtime)
        self.assertIn("runtime-api37/test.apk", runtime)
        build = ci.split("\n  build:\n", 1)[1].split("\n  runtime-test:\n", 1)[0]
        self.assertIn("-PcodexR8Candidate=true", build)
        self.assertIn('--mode full --report "$RUNNER_TEMP/r8-candidate/apk-audit.json"', build)

    def test_target37_and_voice_use_real_device_runner(self):
        root = ROOT / ".github/scripts/run-android-runtime-tests.sh"
        script = root.read_text()
        self.assertIn('adb shell pm grant "$PKG" "$PERMISSION"', script)
        self.assertIn('adb shell pm revoke "$PKG" "$PERMISSION"', script)
        self.assertEqual(script.count('run_suite Android17CandidateInstrumentedTest'), 3)
        self.assertIn('run_suite RealtimeAudioFocusInstrumentedTest "$OUT/audio-focus.log"', script)
        self.assertIn('run_suite RealtimeAudioFocusInstrumentedTest "$OUT/target37/audio-focus.log"', script)
        subprocess.run(["bash", "-n", str(root)], check=True)
