#!/usr/bin/env python3
"""Regression tests for all-route evidence gate, including false-success cases."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import patch

from check_full_feature_workflows import audit


class FullFeatureWorkflowGateTests(TestCase):
    def make_repo(self, root: Path):
        source = root / "android-app/app/src/main/java/io/github/sy220284/codexandroid"
        test = root / "android-app/app/src/androidTest/java/ci"
        tools = root / "android-app/tools"
        docs = root / "docs"
        rust = root / "upstream/codex/codex-rs/app-server-protocol/src/protocol"
        for path in (source, test, tools, docs, rust):
            path.mkdir(parents=True, exist_ok=True)
        (source / "Screens.kt").write_text("fun HomePage() {}", encoding="utf-8")
        (source / "AppNavigation.kt").write_text(
            'Page("home", "首页", "工作台", "开始任务", Icons.Home, "thread/list")',
            encoding="utf-8"
        )
        (source / "UiFeatureCatalog.kt").write_text(
            'UiFeature("home", FeatureSurface.Page, FeatureOwner.Global, false)',
            encoding="utf-8"
        )
        (test / "UiTest.kt").write_text(
            "@Test fun homeOpensScreen() {}",
            encoding="utf-8"
        )
        (docs / "对标应用29项界面交付核对表-20261123.md").write_text(
            "| 01 | `home` | P1 | 首页 | `Screens.kt / HomePage` | homeOpensScreen；home-android35.png |\n",
            encoding="utf-8"
        )
        (docs / "ui-assets").mkdir()
        (docs / "ui-assets/素材接入清单.json").write_text(json.dumps({
            "routes": [{"route": "home", "screens": ["home"]}],
            "requiredStateScreens": [], "optionalStateScreens": [],
        }), encoding="utf-8")
        (tools / "ui_surface_policy.json").write_text(
            '{"methods":{"thread/list":{"surface":"直接操作"}}}',
            encoding="utf-8"
        )
        (rust / "common.rs").write_text("fixture", encoding="utf-8")
        return source, docs, test

    def run_audit(self, root: Path):
        with patch("check_full_feature_workflows.parse_contracts",
                   return_value={"thread/list": SimpleNamespace(direction="client_request")}):
            return audit(root, expected=1)

    def test_registered_page_has_static_evidence_but_no_invented_runtime_success(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_repo(root)
            result = self.run_audit(root)
            self.assertEqual([], result["issues"])
            self.assertEqual("已定位", result["workflows"][0]["static_evidence"])
            self.assertEqual("未逐接口验收", result["workflows"][0]["real_core_receipt"])

    def test_unregistered_source_and_missing_test_or_image_fail_closed(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            _, docs, _ = self.make_repo(root)
            file = docs / "对标应用29项界面交付核对表-20261123.md"
            file.write_text(file.read_text(encoding="utf-8").replace(
                "Screens.kt / HomePage", "Missing.kt"
            ).replace("homeOpensScreen；home-android35.png", "unknownEvidence"))
            errors = self.run_audit(root)["issues"]
            self.assertTrue(any("文件已失效" in error for error in errors))
            self.assertTrue(any("未关联实际 JUnit/Android 用例" in error for error in errors))

    def test_documented_screenshot_must_be_in_device_capture_manifest(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_repo(root)
            manifest = root / "docs/ui-assets/素材接入清单.json"
            manifest.write_text(json.dumps({
                "routes": [{"route": "home", "screens": ["other-screen"]}],
                "requiredStateScreens": [], "optionalStateScreens": [],
            }), encoding="utf-8")
            errors = self.run_audit(root)["issues"]
            self.assertTrue(any("文档截图未纳入设备验收" in error for error in errors))

    def test_screenshot_alone_does_not_prove_a_functional_test_exists(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_repo(root)
            ledger = root / "docs/对标应用29项界面交付核对表-20261123.md"
            ledger.write_text(
                ledger.read_text(encoding="utf-8").replace(
                    "homeOpensScreen；home-android35.png", "home-android35.png"
                ),
                encoding="utf-8"
            )
            errors = self.run_audit(root)["issues"]
            self.assertTrue(any("未关联实际 JUnit/Android 用例" in error
                                for error in errors))

    def test_host_method_cannot_be_marked_as_normal_page_action(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_repo(root)
            with patch("check_full_feature_workflows.parse_contracts",
                       return_value={"thread/list": SimpleNamespace(direction="host_request")}):
                errors = audit(root, expected=1)["issues"]
            self.assertTrue(any("Host" in error for error in errors))

    def test_missing_feature_catalog_entry_blocks_full_coverage(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source, _, _ = self.make_repo(root)
            (source / "UiFeatureCatalog.kt").write_text("", encoding="utf-8")
            errors = self.run_audit(root)["issues"]
            self.assertTrue(any("缺少功能目录" in error for error in errors))
