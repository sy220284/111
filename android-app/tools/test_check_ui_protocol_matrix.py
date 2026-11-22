#!/usr/bin/env python3
import json
import unittest

from check_ui_protocol_matrix import verify

METHODS = [f"test{i}/action" for i in range(110)]
OFFICIAL = "\n".join(f'case => "{method}" {{' for method in METHODS)
MATRIX = "\n".join(
    f'ProtocolSurface("{method}", ApiExposure.Direct, "测试", false),'
    for method in METHODS
)
POLICY = json.dumps({"methods": {
    method: {"surface": "直接操作"} for method in METHODS
}})


class ProtocolMatrixTests(unittest.TestCase):
    def test_matching_catalog(self):
        self.assertEqual([], verify(MATRIX, POLICY, OFFICIAL))

    def test_missing_kotlin_method_is_detected(self):
        broken = MATRIX.replace('ProtocolSurface("test0/action", ApiExposure.Direct, "测试", false),', "")
        self.assertTrue(any("缺少：test0/action" in err
            for err in verify(broken, POLICY, OFFICIAL)))

    def test_exposure_mismatch_is_detected(self):
        broken = MATRIX.replace(
            'ProtocolSurface("test1/action", ApiExposure.Direct',
            'ProtocolSurface("test1/action", ApiExposure.Internal')
        self.assertTrue(any("分层不一致：test1/action" in err
            for err in verify(broken, POLICY, OFFICIAL)))


if __name__ == "__main__":
    unittest.main()
