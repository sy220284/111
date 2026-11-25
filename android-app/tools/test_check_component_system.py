import unittest
from check_component_system import audit, SHARED, without_comments_and_literals

def fixture():
    return {"BusinessPage": "\n".join(name + "()" for name in SHARED)}

class ComponentSystemTest(unittest.TestCase):
    def test_shared_calls_and_native_implementation(self):
        sources = fixture()
        sources["VisualSystem"] = "fun CodexPrimaryButton() { Button() }"
        sources["CodexControlPrimitives"] = "fun CodexInputField() { OutlinedTextField() }"
        self.assertEqual(audit(sources)["errors"], [])

    def test_direct_display_dimensions_and_system_only_font_scale_are_rejected(self):
        sources = fixture()
        sources["BusinessPage"] += "\nscreenHeightDp\nLocalDensity.current.fontScale\nproduceState<Int>(0) {}"
        errors = audit(sources)["errors"]
        self.assertEqual(len(errors), 3)
        self.assertTrue(any("screenHeightDp" in error for error in errors))
        self.assertTrue(any("fontScale" in error for error in errors))
        self.assertTrue(any("produceCodexUiState" in error for error in errors))

    def test_shared_window_source_is_allowed(self):
        sources = fixture()
        sources["CodexViewport"] = "val height = config.screenHeightDp\nval size = LocalDensity.current.fontScale"
        sources["CodexUiAsyncState"] = "produceState(0) {}"
        self.assertEqual(audit(sources)["errors"], [])

    def test_legacy_control_in_business_page_rejected(self):
        sources = fixture()
        sources["BusinessPage"] += "\nOutlinedTextField()\nTextButton()"
        errors = audit(sources)["errors"]
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("OutlinedTextField" in error for error in errors))
        self.assertTrue(any("TextButton" in error for error in errors))

    def test_segmented_controls_are_centralized(self):
        sources = fixture()
        sources["BusinessPage"] += "\nSegmentedButton()\nSingleChoiceSegmentedButtonRow()"
        errors = audit(sources)["errors"]
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("SingleChoiceSegmentedButtonRow" in error for error in errors))

    def test_declaration_and_test_only_do_not_count(self):
        sources = fixture()
        sources["BusinessPage"] = sources["BusinessPage"].replace("CodexInputField()", "")
        sources["CodexControlPrimitives"] = "fun CodexInputField() {}"
        self.assertTrue(any("CodexInputField: 缺少" in x for x in audit(sources)["errors"]))

    def test_comments_and_strings_do_not_count(self):
        sources = fixture()
        sources["BusinessPage"] += '\n// Button()\nval text = "OutlinedTextField()"'
        self.assertEqual(audit(sources)["errors"], [])
        self.assertEqual(without_comments_and_literals("// Button()\n").count("\n"), 1)

if __name__ == "__main__":
    unittest.main()
