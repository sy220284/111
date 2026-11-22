import tempfile
import unittest
from pathlib import Path

from prepare_android_mxc import AUDIT_CALL
from prepare_android_code_mode import DISCOVERY_ANCHOR, HOST_CLI_ANCHOR, HOST_MAIN_ANCHOR
from rehearse_upstream_upgrade import rehearse


class UpgradeRehearsalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.codex = root / "candidate"
        self.cargo = root / "cargo"
        for name in ("Cargo.toml", "Cargo.lock", "app-server/Cargo.toml"):
            path = self.codex / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("[package]\nname='candidate'\nversion='0.0.1'\n")
        (self.codex / "Cargo.toml").write_text(
            "[workspace.dependencies]\n"
            "mxc-sdk = { git = 'https://github.com/microsoft/mxc', "
            "rev = 'c66201839d63cae2239db5e92f1f1d6076cf5181' }\n"
        )
        self.install = self.codex / "install-context/src/lib.rs"
        self.host = self.codex / "code-mode-host/src/main.rs"
        self.install.parent.mkdir(parents=True)
        self.host.parent.mkdir(parents=True)
        self.install.write_text(DISCOVERY_ANCHOR + "\n")
        self.host.write_text(HOST_CLI_ANCHOR + "\n" + HOST_MAIN_ANCHOR + "\n")
        self.mxc = (self.cargo / "git/checkouts/mxc-test/c662018/src/"
                    "mxc-sdk/src/core/mxc_engine/dispatch.rs")
        self.mxc.parent.mkdir(parents=True)
        self.mxc.write_text("fn source() {\n" + ("    " + AUDIT_CALL + "\n") * 3 + "}\n")

    def tearDown(self):
        self.temp.cleanup()

    def test_checks_three_sites_and_preserves_every_source(self):
        originals = {p: p.read_bytes() for p in (self.install, self.host, self.mxc)}
        report = rehearse(self.codex, self.cargo)
        self.assertEqual(3, report["guarded_mxc_calls"])
        self.assertEqual(2, len(report["code_mode_sources"]))
        for path, contents in originals.items():
            self.assertEqual(contents, path.read_bytes())

    def test_rejects_candidate_revision_drift_even_with_old_checkout_present(self):
        manifest = self.codex / "Cargo.toml"
        manifest.write_text(manifest.read_text().replace(
            "c66201839d63cae2239db5e92f1f1d6076cf5181", "a" * 40))
        with self.assertRaisesRegex(RuntimeError, "固定版本不一致"):
            rehearse(self.codex, self.cargo)

    def test_rejects_unpinned_mxc_revision(self):
        self.mxc.rename(self.mxc.with_name("old.rs"))
        with self.assertRaisesRegex(RuntimeError, "uniquely pinned"):
            rehearse(self.codex, self.cargo)

    def test_rejects_upstream_code_mode_drift(self):
        self.host.write_text("fn unrelated() {}\n")
        with self.assertRaisesRegex(ValueError, "upstream changed"):
            rehearse(self.codex, self.cargo)

    def test_rejects_mxc_call_count_drift(self):
        self.mxc.write_text("fn source(){ " + AUDIT_CALL + " }\n")
        with self.assertRaisesRegex(RuntimeError, "expected exactly three"):
            rehearse(self.codex, self.cargo)


if __name__ == "__main__":
    unittest.main()
