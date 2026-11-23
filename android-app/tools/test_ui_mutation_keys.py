#!/usr/bin/env python3
"""技能与插件状态变更须使用独立请求键，检索结果绑定准确查询。"""
from pathlib import Path
import re
import unittest

SOURCE = Path("android-app/app/src/main/java/io/github/sy220284/codexandroid/EcosystemScreens.kt")


class MutationKeyTests(unittest.TestCase):
    def test_mutations_do_not_reuse_list_request_keys(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertRegex(source, r'vm\.rpc\("skillsWrite",\s*"skills/config/write"')
        self.assertRegex(source, r'vm\.rpc\("pluginMutation",\s*"plugin/install"')
        self.assertRegex(source, r'vm\.rpc\("pluginMutation",\s*"plugin/uninstall"')
        self.assertNotRegex(source, r'vm\.rpc\("(?:skills|plugins)",\s*"(?:skills/config/write|plugin/(?:install|uninstall))"')

    def test_plugin_search_cannot_show_stale_results(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("ThreadSearchSubmission.matches(searchDraft, searchSubmitted)", source)
        self.assertIn('data[searchPrefix + "0"] != null', source)


if __name__ == "__main__":
    unittest.main()
