#!/usr/bin/env python3
import json
import unittest

from check_ui_protocol_matrix import verify, verify_ui_entrypoints

METHODS = [f"test{i}/action" for i in range(110)]
CLIENT = "client_request_definitions! {\n" + "\n".join(
    f'    Test{i} => "{method}" {{\n'
    f'        params: v2::TestParams,\n'
    f'        response: v2::TestResponse,\n'
    f'    }},' for i, method in enumerate(METHODS)
) + "\n}\n"
HOST = ('server_request_definitions! {\n'
        '    Prompt => "host/approval" {\n'
        '        params: v2::ApprovalParams,\n'
        '        response: v2::ApprovalResponse,\n'
        '    },\n}\n')
OFFICIAL = CLIENT + HOST
MATRIX = "\n".join(
    f'ProtocolSurface("{method}", ApiExposure.Direct, "测试", false),'
    for method in METHODS
) + '\nProtocolSurface("host/approval", ApiExposure.Internal, "后台", false),'
POLICY = json.dumps({"methods": {
    **{method: {"surface": "直接操作", "owner": "测试", "flag": "常规/内部"}
       for method in METHODS},
    "host/approval": {"surface": "后台内部", "owner": "后台", "flag": "常规/内部"},
}}, ensure_ascii=False)


class ProtocolMatrixTests(unittest.TestCase):
    def test_matching_catalog(self):
        self.assertEqual([], verify(MATRIX, POLICY, OFFICIAL))

    def test_missing_kotlin_method_is_detected(self):
        broken = MATRIX.replace(
            'ProtocolSurface("test0/action", ApiExposure.Direct, "测试", false),', "")
        self.assertTrue(any("缺少：test0/action" in err
                            for err in verify(broken, POLICY, OFFICIAL)))

    def test_exposure_mismatch_is_detected(self):
        broken = MATRIX.replace('ProtocolSurface("test1/action", ApiExposure.Direct',
                                'ProtocolSurface("test1/action", ApiExposure.Internal')
        self.assertTrue(any("分层不一致：test1/action" in err
                            for err in verify(broken, POLICY, OFFICIAL)))

    def test_owner_mismatch_is_detected(self):
        broken = MATRIX.replace(
            'ProtocolSurface("test1/action", ApiExposure.Direct, "测试"',
            'ProtocolSurface("test1/action", ApiExposure.Direct, "错误归属"')
        self.assertTrue(any("功能归属不一致：test1/action" in err
                            for err in verify(broken, POLICY, OFFICIAL)))

    def test_experimental_tag_mismatch_is_detected(self):
        broken = MATRIX.replace(
            'ProtocolSurface("test2/action", ApiExposure.Direct, "测试", false)',
            'ProtocolSurface("test2/action", ApiExposure.Direct, "测试", true)')
        self.assertTrue(any("实验标记不一致：test2/action" in err
                            for err in verify(broken, POLICY, OFFICIAL)))

    def test_server_initiated_requests_do_not_become_ui_buttons(self):
        broken = MATRIX.replace(
            'ProtocolSurface("host/approval", ApiExposure.Internal',
            'ProtocolSurface("host/approval", ApiExposure.Direct')
        self.assertTrue(any("Host 主动请求" in err
                            for err in verify(broken, POLICY, OFFICIAL)))

    def test_internal_protocol_is_never_a_navigation_action(self):
        policy = json.dumps({"methods": {
            "hooks/list": {"surface": "后台内部"},
            "mcpServerStatus/list": {"surface": "直接操作"}
        }}, ensure_ascii=False)
        pages = ('Page("mcp", "MCP 服务", "能力", "连接管理", Icons.Hub, "mcpServerStatus/list"),\n'
                 'Page("hooks", "运行时钩子", "能力", "引擎内部", Icons.Bolt, "hooks/list"),')
        features = 'UiFeature("mcp", FeatureSurface.Page), UiFeature("hooks", FeatureSurface.Page)'
        errors = verify_ui_entrypoints(pages, features, policy)
        self.assertTrue(any("后台接口被放入 UI 页面" in error for error in errors))

    def test_visible_service_settings_can_exist_without_tool_buttons(self):
        policy = json.dumps({"methods": {
            "hooks/list": {"surface": "后台内部"},
            "mcpServerStatus/list": {"surface": "直接操作"}
        }}, ensure_ascii=False)
        pages = 'Page("mcp", "MCP 服务", "能力", "连接管理", Icons.Hub, "mcpServerStatus/list"),'
        features = ('UiFeature("mcp", FeatureSurface.Page),\n'
                    'UiFeature("update", FeatureSurface.Inline)')
        self.assertEqual([], verify_ui_entrypoints(pages, features, policy))

    def test_page_missing_from_catalog_is_rejected(self):
        policy = json.dumps({"methods": {}}, ensure_ascii=False)
        errors = verify_ui_entrypoints(
            'Page("mcp", "MCP 服务", Icons.Hub)',
            'UiFeature("settings", FeatureSurface.Page)', policy)
        self.assertTrue(any("页面与功能目录不一致" in error for error in errors))

    def test_unknown_policy_flag_is_detected(self):
        p = json.loads(POLICY)
        p["methods"]["test5/action"]["flag"] = "实验"
        self.assertTrue(any("实验标记不一致：test5/action" in err
                            for err in verify(MATRIX, json.dumps(p), OFFICIAL)))


if __name__ == "__main__":
    unittest.main()
