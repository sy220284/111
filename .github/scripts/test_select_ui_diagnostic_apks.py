import unittest
from unittest.mock import patch
from select_ui_diagnostic_apks import select

class DiagnosticSelectionTests(unittest.TestCase):
    def test_only_original_successful_compile_proof(self):
        proof={"source":"a"*40,"run_id":12,"artifacts":{"codex-android-ui-apks":{"id":1},"ui-apk-integrity":{"id":2}}}
        with patch("select_ui_diagnostic_apks.api",return_value={"id":12}) as api, patch("select_ui_diagnostic_apks.candidate",return_value={"compile":proof}) as candidate:
            self.assertEqual(proof,select("o/r","12","a"*40))
            api.assert_called_once_with("o/r","actions/runs/12")
            self.assertEqual("a"*40,candidate.call_args.args[2])
        for result in (None,{},{"ui":proof}):
            with patch("select_ui_diagnostic_apks.api",return_value={"id":12}), patch("select_ui_diagnostic_apks.candidate",return_value=result), self.assertRaises(ValueError):
                select("o/r","12","b"*40)
        with self.assertRaises(ValueError):
            select("o/r","../12","a"*40)
