#!/usr/bin/env python3
"""Regression test for palette drift detection."""
import unittest
from check_design_system_tokens import verify

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


if __name__ == '__main__':
    unittest.main()
