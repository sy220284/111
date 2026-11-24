#!/usr/bin/env python3
"""Regression test for palette drift detection."""
import unittest
import json
from check_design_system_tokens import verify, verify_asset_tokens, verify_motion_tokens, MOTION_KEYS

SOURCE = '''object Ink {
    val canvas = Color(0xFFFFFFFF)
    val recessed = Color(0xFFFFFFFF)
    val panel = Color(0xFFFFFFFF)
    val elevated = Color(0xFFFFFFFF)
    val border = Color(0xFFD8DEE1)
    val parchment = Color(0xFF263139)
    val muted = Color(0xFF56646D)
    val coral = Color(0xFFC8483E)
    val copper = Color(0xFF805A3F)
    val jade = Color(0xFF246F54)
    val warning = Color(0xFF8A6437)
    val red = Color(0xFFAC363A)
}
val scheme = lightColorScheme(primary = Ink.coral, onPrimary = Ink.canvas)
'''
MARKDOWN = '''<!-- INK_TOKENS_BEGIN -->
| Compose 标识 | HEX | 使用范围 |
| --- | --- | --- |
''' + '\n'.join(
    f'| `{name}` | `#{value}` | 说明 |'
    for name, value in [
        ('canvas', 'FFFFFF'), ('recessed', 'FFFFFF'), ('panel', 'FFFFFF'),
        ('elevated', 'FFFFFF'), ('border', 'D8DEE1'), ('parchment', '263139'),
        ('muted', '56646D'), ('coral', 'C8483E'), ('copper', '805A3F'),
        ('jade', '246F54'), ('warning', '8A6437'), ('red', 'AC363A')]
) + '\n<!-- INK_TOKENS_END -->'


class DesignTokenGateTests(unittest.TestCase):
    def test_matching_palette(self):
        self.assertEqual([], verify(SOURCE, MARKDOWN))

    def test_missing_field_is_rejected(self):
        bad = MARKDOWN.replace('| `coral` | `#C8483E` | 说明 |', '')
        self.assertTrue(any('缺失 Ink.coral' in err for err in verify(SOURCE, bad)))

    def test_wrong_color_is_rejected(self):
        bad = MARKDOWN.replace('#C8483E', '#FFFFFF')
        self.assertTrue(any('颜色不一致' in err for err in verify(SOURCE, bad)))

    def test_primary_theme_drift_is_rejected(self):
        bad = SOURCE.replace('primary = Ink.coral', 'primary = Ink.copper')
        self.assertTrue(any('MaterialTheme.primary' in err for err in verify(bad, MARKDOWN)))

    def test_dual_theme_requires_documented_dark_variables(self):
        derived = SOURCE.replace('object Ink {', 'object InkLight {')
        derived += '\nobject InkDark {\n    val canvas = Color(0xFF111A22)\n}\n'
        derived += 'object Ink { val canvas: Color get() = Color(0xFFFFFFFF) }\n'
        derived += 'fun example() = darkColorScheme(primary = Ink.coral)\n'
        self.assertTrue(any('深色' in error or '未绑定' in error
                            for error in verify(derived, MARKDOWN)))


    def test_figma_assets_use_the_same_semantic_color_keys(self):
        source = '''
private object InkLight {
    val canvas = Color(0xFFFFFFFF)
    val parchment = Color(0xFF263139)
}
private object InkDark {
    val canvas = Color(0xFF111A22)
    val parchment = Color(0xFFF0F2F3)
}
'''
        tokens = {"modes": {
            "light": {"canvas": "#FFFFFF", "parchment": "#263139"},
            "dark": {"canvas": "#111A22", "parchment": "#F0F2F3"}
        }, "sizes": {"touchTargetDp": 48}}
        self.assertEqual([], verify_asset_tokens(source, json.dumps(tokens)))
        tokens["modes"]["dark"]["parchment"] = "#FFFFFF"
        self.assertTrue(any("dark Ink.parchment" in error
                            for error in verify_asset_tokens(source, json.dumps(tokens))))

    def test_figma_tokens_need_complete_modes_and_touch_target(self):
        self.assertTrue(verify_asset_tokens("", "{}"))
        source = '''
private object InkLight {
    val canvas = Color(0xFFFFFFFF)
}
private object InkDark {
    val canvas = Color(0xFF111A22)
}
'''
        tokens = {"modes": {"light": {"canvas": "#FFFFFF"},
                            "dark": {"canvas": "#111A22"}},
                  "sizes": {"touchTargetDp": 32}}
        self.assertTrue(any("48dp" in error
                            for error in verify_asset_tokens(source, json.dumps(tokens))))

    def test_motion_tokens_require_matching_values_and_reduced_motion(self):
        values = {key: 100 + i * 20 for i, key in enumerate(MOTION_KEYS)}
        source = "object CodexMotion {\n" + "\n".join(
            f"    const val {constant} = {values[key]}"
            for key, constant in MOTION_KEYS.items()) + "\n}"
        tokens = {"motionMs": {**values, "reduced": 0}}
        self.assertEqual([], verify_motion_tokens(source, json.dumps(tokens)))
        tokens["motionMs"]["page"] += 10
        self.assertTrue(any("navigation" in err for err in verify_motion_tokens(source, json.dumps(tokens))))
        tokens["motionMs"]["reduced"] = 100
        self.assertTrue(any("减少动效" in err for err in verify_motion_tokens(source, json.dumps(tokens))))
        del tokens["motionMs"]["press"]
        self.assertTrue(verify_motion_tokens(source, json.dumps(tokens)))

if __name__ == '__main__':
    unittest.main()
