import pathlib
import tempfile
import unittest
from unittest.mock import patch

import auxiliary


class PortableAuxiliaryTest(unittest.TestCase):
    def test_only_product_auxiliary_components_are_supported(self):
        self.assertEqual(auxiliary.GROUPS, ("wheelhouse", "emulator", "llvm", "patcher"))

    def test_emulator_production_uses_and_archives_only_exact_command_line_tools(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp); sdk=root/"sdk"
            revision=auxiliary.LOCK["deviceCommandLineTools"]
            package="system-images/android-"+auxiliary.LOCK["deviceImage"]+"/"+auxiliary.LOCK["deviceTarget"]+"/x86_64"
            for rel in ("cmdline-tools/12.0/bin","cmdline-tools/"+revision+"/bin", "emulator", "platform-tools", "licenses",
                        "platforms/android-"+auxiliary.EMULATOR_SDK_PLATFORM,package):
                (sdk/rel).mkdir(parents=True)
            for version in ("12.0",revision):
                (sdk/"cmdline-tools"/version/"bin/sdkmanager").write_text("manager")
                (sdk/"cmdline-tools"/version/"source.properties").write_text("Pkg.Revision="+version+"\n")
            (sdk/"emulator/source.properties").write_text("emulator")
            (sdk/package/"source.properties").write_text("image")
            with patch.dict(auxiliary.os.environ,{"ANDROID_HOME":str(sdk)}),patch.object(auxiliary,"run") as run:
                stage=root/"stage"
                auxiliary.prepare("emulator",stage)
                self.assertEqual(run.call_args_list[0].args[1],"cmdline-tools;"+revision)
                self.assertEqual(run.call_args_list[1].args[0],sdk/"cmdline-tools"/revision/"bin/sdkmanager")
                self.assertEqual([revision],sorted(p.name for p in (stage/"cmdline-tools").iterdir()))


if __name__ == "__main__":
    unittest.main()
