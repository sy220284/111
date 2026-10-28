#!/usr/bin/env python3
"""111 工具链离线组件契约测试：复用 777 的产物校验、按需安装检查方式。"""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
CLI = REPO / "tools/dev/ai-toolchain.sh"


class ToolchainTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.env = os.environ.copy()
        self.env["DEV111_TOOLS_ROOT"] = str(self.root / "tools")
        self.env["DEV111_STATE_DIR"] = str(self.root / "state")
        self.env["DEV111_TOOLCHAIN_ENV_FILE"] = str(self.root / "env")

    def call(self, *args):
        return subprocess.run(["bash", str(CLI), *args], cwd=REPO, env=self.env,
                              text=True, capture_output=True, check=False)

    def test_plan_is_json_and_has_expected_profile(self):
        proc = self.call("plan", "--profile", "full")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        result = json.loads(proc.stdout)
        self.assertEqual(result["profile"], "full")
        self.assertIn("missing_artifacts", result)

    def test_compat_profile_is_recognized(self):
        proc = self.call("plan", "--profile", "compat")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(json.loads(proc.stdout)["profile"], "compat")

    def test_analysis_profile_includes_missing_jelly(self):
        p = self.call("plan", "--profile", "analysis")
        self.assertEqual(p.returncode, 0, p.stderr)
        data = json.loads(p.stdout)
        self.assertEqual(data["profile"], "analysis")
        self.assertIn("111-toolchain-jelly-latest", data["missing_artifacts"])

    def test_jelly_artifact_requires_exact_version(self):
        folder = self.root / "111-toolchain-jelly-latest"
        folder.mkdir()
        values = dict(line.split("=", 1) for line in
                      (REPO / "tools/dev/toolchain-versions.env").read_text().splitlines()
                      if "=" in line and not line.startswith("#"))
        (folder / "component.env").write_text(
            "ARTIFACT_SCHEMA=1\\nARTIFACT_COMPONENT=jelly\\nARTIFACT_ARCH=x64\\n"
            f"ARTIFACT_NODE_VERSION={values['NODE_VERSION']}\\n"
            "ARTIFACT_JELLY_VERSION=0.0.0\\n"
        )
        with tarfile.open(folder / "payload.tar.gz", "w:gz") as tar:
            data = b"fake"
            info = tarfile.TarInfo("jelly/node_modules/@cs-au-dk/jelly/lib/main.js")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        (folder / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256((folder / p).read_bytes()).hexdigest()}  {p}\\n"
            for p in ["component.env", "payload.tar.gz"]
        ))
        self.assertNotEqual(self.call("verify-artifact", "jelly", str(folder)).returncode, 0)

    def test_optional_bun_manifest_version_is_required(self):
        folder = self.root / "111-toolchain-bun-latest"
        folder.mkdir()
        versions = (REPO / "tools/dev/toolchain-versions.env").read_text()
        values = dict(line.split("=", 1) for line in versions.splitlines()
                      if "=" in line and not line.startswith("#"))
        meta = (
            "ARTIFACT_SCHEMA=1\nARTIFACT_COMPONENT=bun\nARTIFACT_ARCH=x64\n"
            f"ARTIFACT_NODE_VERSION={values['NODE_VERSION']}\n"
            "ARTIFACT_BUN_VERSION=0.0.0\n"
        )
        (folder / "component.env").write_text(meta)
        with tarfile.open(folder / "payload.tar.gz", "w:gz") as tar:
            data = b"dummy bun binary"
            info = tarfile.TarInfo("bin/bun")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        checks = "".join(
            f"{hashlib.sha256((folder / f).read_bytes()).hexdigest()}  {f}\n"
            for f in ["component.env", "payload.tar.gz"]
        )
        (folder / "SHA256SUMS").write_text(checks)
        self.assertNotEqual(self.call("verify-artifact", "bun", str(folder)).returncode, 0)

    def test_unknown_profile_fails(self):
        self.assertNotEqual(self.call("plan", "--profile", "other").returncode, 0)

    def make_cache_artifact(self, unsafe=False, mismatched=False):
        folder = self.root / "111-toolchain-npm-cache-latest"
        folder.mkdir()
        versions = (REPO / "tools/dev/toolchain-versions.env").read_text()
        node_version = next(s.split("=", 1)[1] for s in versions.splitlines()
                            if s.startswith("NODE_VERSION="))
        lock_hash = hashlib.sha256((REPO / "package-lock.json").read_bytes()).hexdigest()
        if mismatched:
            lock_hash = "0" * 64
        meta = (
            "ARTIFACT_SCHEMA=1\nARTIFACT_COMPONENT=npm-cache\n"
            "ARTIFACT_ARCH=x64\n"
            f"ARTIFACT_NODE_VERSION={node_version}\n"
            f"ARTIFACT_LOCK_SHA256={lock_hash}\n"
        )
        (folder / "component.env").write_text(meta)
        with tarfile.open(folder / "payload.tar.gz", "w:gz") as tar:
            data = b"offline cache fixture"
            info = tarfile.TarInfo("../escape" if unsafe else "npm-cache/_cacache/test")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        checks = "".join(
            f"{hashlib.sha256((folder / f).read_bytes()).hexdigest()}  {f}\n"
            for f in ["component.env", "payload.tar.gz"]
        )
        (folder / "SHA256SUMS").write_text(checks)
        return folder

    def test_artifact_verification_accepts_correct_manifest(self):
        folder = self.make_cache_artifact()
        result = self.call("verify-artifact", "npm-cache", str(folder))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_lock_drift_is_rejected(self):
        folder = self.make_cache_artifact(mismatched=True)
        self.assertNotEqual(self.call("verify-artifact", "npm-cache", str(folder)).returncode, 0)

    def test_checksum_tampering_is_rejected(self):
        folder = self.make_cache_artifact()
        with (folder / "payload.tar.gz").open("ab") as out:
            out.write(b"tampered")
        self.assertNotEqual(self.call("verify-artifact", "npm-cache", str(folder)).returncode, 0)

    def test_path_traversal_is_rejected(self):
        folder = self.make_cache_artifact(unsafe=True)
        self.assertNotEqual(self.call("verify-artifact", "npm-cache", str(folder)).returncode, 0)


if __name__ == "__main__":
    unittest.main()
