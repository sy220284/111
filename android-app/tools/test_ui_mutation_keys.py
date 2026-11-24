#!/usr/bin/env python3
"""技能与插件状态变更须使用独立请求键，检索结果绑定准确查询。"""
from pathlib import Path
import re
import unittest

SOURCE_DIR = Path("android-app/app/src/main/java/io/github/sy220284/codexandroid")
SKILLS = SOURCE_DIR / "SkillsPage.kt"
PLUGINS = SOURCE_DIR / "EcosystemScreens.kt"
EXTENSIONS = SOURCE_DIR / "ExtensionCatalogController.kt"


class MutationKeyTests(unittest.TestCase):
    def test_mutations_do_not_reuse_list_request_keys(self):
        source = SKILLS.read_text(encoding="utf-8") + "\n" + PLUGINS.read_text(encoding="utf-8")
        self.assertRegex(source, r'vm\.rpc\("skillsWrite",\s*"skills/config/write"')
        controller = EXTENSIONS.read_text(encoding="utf-8")
        self.assertRegex(source, r'vm\.extensions\.changePlugin\("plugin/install"')
        self.assertRegex(source, r'vm\.extensions\.changePlugin\("plugin/uninstall"')
        self.assertRegex(controller, r'fun changePlugin\(method: String, params: JSONObject, workspace: String\)')
        self.assertRegex(controller, r'vm\.rpc\("pluginMutation",\s*method,\s*params')
        self.assertNotRegex(source + controller, r'vm\.rpc\("(?:skills|plugins)",\s*"(?:skills/config/write|plugin/(?:install|uninstall))"')
        self.assertNotRegex(controller, r'vm\.rpc\("(?:skills|plugins)",\s*method')

    def test_plugin_search_cannot_show_stale_results(self):
        source = PLUGINS.read_text(encoding="utf-8")
        self.assertIn("ThreadSearchSubmission.matches(searchDraft, searchSubmitted)", source)
        self.assertIn('data[searchPrefix + "0"] != null', source)


if __name__ == "__main__":
    unittest.main()
