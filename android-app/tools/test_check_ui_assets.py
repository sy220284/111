#!/usr/bin/env python3
import unittest
import xml.etree.ElementTree as ET
from check_ui_assets import icon_paths, verify_icon, verify_artwork, path_tokens, invalid_glyph_namespace

def svg(path):
    return ET.fromstring('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="'+path+'"/></svg>')

def vector(path):
    return ET.fromstring('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:pathData="'+path+'" android:strokeWidth="1.8" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"/></vector>')

class AssetChecks(unittest.TestCase):
    def test_glyph_type_namespace(self):
        self.assertTrue(invalid_glyph_namespace("icon: androidx.compose.ui.graphics.vector.CodexGlyph"))
        self.assertFalse(invalid_glyph_namespace("icon: CodexGlyph"))
        self.assertFalse(invalid_glyph_namespace("icon: io.github.sy220284.codexandroid.CodexGlyph"))

    def test_identical_geometry(self):
        verify_icon(svg("M3 5L12 7"), vector("M3,5 L12,7"))

    def test_mismatched_geometry_fails(self):
        with self.assertRaises(ValueError):
            verify_icon(svg("M3 5L12 7"), vector("M3 5L13 7"))

    def test_missing_path_fails(self):
        with self.assertRaises(ValueError):
            verify_icon(svg("M3 5L12 7"),ET.fromstring('<vector/>'))

    def test_circle_rect_conversion(self):
        doc=ET.fromstring('<svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><rect x="3" y="4" width="18" height="16" rx="3"/></svg>')
        paths=icon_paths(doc)
        self.assertEqual(path_tokens(paths[0]),path_tokens("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z"))
        self.assertEqual(path_tokens(paths[1]),path_tokens("M6 4h12q3 0 3 3v10q0 3 -3 3h-12q-3 0 -3 -3v-10q0 -3 3 -3Z"))

    def test_artwork_color_mismatch_fails(self):
        a=ET.fromstring('<svg viewBox="0 0 152 116"><path d="M1 1L3 3" fill="#FFFFFF"/></svg>')
        b=ET.fromstring('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:viewportWidth="152" android:viewportHeight="116"><path android:pathData="M1 1L3 3" android:fillColor="#111111"/></vector>')
        with self.assertRaises(ValueError):
            verify_artwork(a,b)

if __name__=="__main__":
    unittest.main()
