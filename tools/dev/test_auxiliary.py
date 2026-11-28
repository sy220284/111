import pathlib
import tempfile
import unittest
from unittest.mock import patch

import auxiliary


class PortableAuxiliaryTest(unittest.TestCase):
    def test_only_product_auxiliary_components_are_supported(self):
        self.assertEqual(auxiliary.GROUPS, ("node", "wheelhouse", "emulator", "llvm", "patcher"))

    def test_emulator_production_provisions_all_pinned_packages_directly_in_the_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage = pathlib.Path(tmp) / "stage"
            with patch.object(auxiliary, "install_android") as install:
                auxiliary.prepare("emulator", stage)
            self.assertEqual(
                [(stage, name) for name in ("cmdline", "adb", "emulator", "image", "emulator-platform")],
                [call.args for call in install.call_args_list],
            )


if __name__ == "__main__":
    unittest.main()
