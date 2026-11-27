#!/usr/bin/env python3
import unittest

from audit_ui_feature_coverage import find_rpc_calls, audit_surface_hierarchy, declared_method_resolvers


class SourceCallsiteClassificationTest(unittest.TestCase):
    def test_metadata_literals_do_not_count_as_calls(self):
        source = '''
        ProtocolSurface("thread/read", ApiExposure.Direct, "会话", false)
        val label = "thread/read"
        val method = "thread/read"
        '''
        self.assertEqual(set(), find_rpc_calls(source, {"thread/read"}))

    def test_direct_calls_are_detected_across_lines(self):
        source = '''
        bridge.request("thread/read", JSONObject())
        vm.rpc("save", "thread/goal/set",
            JSONObject().put("threadId", id))
        vm.refresh("threads",
            "thread/list", JSONObject())
        '''
        self.assertEqual({"thread/read", "thread/goal/set", "thread/list"},
                         find_rpc_calls(source, {
                             "thread/read", "thread/goal/set", "thread/list"
                         }))

    def test_task_owner_calls_use_method_after_view_model(self):
        code = '''
        owner.request(vm, "command/exec/resize", params)
        lease.request(vm, "turn/start", params)
        owner.request(this@CodexViewModel, "review/start", params)
        owner.request("thread/delete", "thread/archive", params)
        val method = "thread/compact/start"
        '''
        methods = {"command/exec/resize", "turn/start", "review/start",
                   "thread/delete", "thread/archive", "thread/compact/start"}
        self.assertEqual({"command/exec/resize", "turn/start", "review/start"},
                         find_rpc_calls(code, methods))

    def test_capability_requests_count_only_the_method_argument(self):
        source = 'vm.capabilityRequest("memory/status", JSONObject().put("action", "memory/reset"))'
        self.assertEqual({"memory/status"}, find_rpc_calls(source, {"memory/status", "memory/reset"}))

    def test_dynamic_routing_is_not_wrongly_marked_concrete(self):
        source = 'vm.rpc("execute", method, params)'
        self.assertEqual(set(), find_rpc_calls(source, {"thread/delete"}))


    def test_concatenated_keys_and_nested_calls_are_detected(self):
        code = '''
        vm.refresh(prefix + nextPage, "thread/turns/list",
            JSONObject().put("threadId", id))
        vm.rpc(if (active) "interrupt" else "interrupt:" + id,
            "turn/interrupt", JSONObject().put("turnId", id))
        vm.refresh("pluginSearch:" + term + ":0",
            "plugin/search", PluginSearchContract.params(term))
        '''
        expected = {"thread/turns/list", "turn/interrupt", "plugin/search"}
        self.assertEqual(expected, find_rpc_calls(code, expected))

    def test_when_method_dispatch_is_counted_only_in_request(self):
        code = '''
        val description = "thread/unarchive"
        vm.rpc("library", when (kind) {
            "delete" -> "thread/delete"
            "archive" -> "thread/archive"
            else -> "thread/unarchive"
        }, JSONObject().put("threadId", id))
        '''
        expected = {"thread/delete", "thread/archive", "thread/unarchive"}
        self.assertEqual(expected, find_rpc_calls(code, expected))

    def test_local_method_variable_requires_an_actual_request(self):
        code = '''
        val method = if (usage) "account/usage/read" else "account/rateLimits/read"
        val doc = "account/usage/read"
        vm.refresh(selectedTab, method)
        '''
        methods = {"account/usage/read", "account/rateLimits/read"}
        self.assertEqual(methods, find_rpc_calls(code, methods))
        self.assertEqual(set(), find_rpc_calls(code.split("vm.refresh")[0], methods))

    def test_organization_dispatch_traces_checked_policy_only_at_actual_request(self):
        policy = """
        fun method(kind: String): String = when (kind) {
            "archive" -> "thread/archive"
            "unarchive" -> "thread/unarchive"
            "delete" -> "thread/delete"
            else -> throw IllegalArgumentException("unknown")
        }
        """
        methods = {"thread/archive", "thread/unarchive", "thread/delete"}
        resolvers = declared_method_resolvers({"ThreadBulkActionPolicy.kt": policy}, methods)
        actual = 'val method = try { ThreadBulkActionPolicy.method(kind) }'
        self.assertEqual(set(), find_rpc_calls(actual, methods, resolvers))
        self.assertEqual(methods, find_rpc_calls(actual + '\nbridge.request(method, params)', methods, resolvers))
        self.assertEqual(set(), find_rpc_calls(actual + '\nbridge.request(method, params)', methods, {}))

    def test_unrelated_method_literals_do_not_prove_execution(self):
        code = '''
        val description = "thread/archive"
        vm.rpc("save", method, params)
        '''
        self.assertEqual(set(), find_rpc_calls(code, {"thread/archive"}))



class SurfaceHierarchyContractTest(unittest.TestCase):
    @staticmethod
    def fixture():
        catalog = '''
        UiFeature("home", FeatureSurface.Page, FeatureOwner.Global, false)
        UiFeature("threads", FeatureSurface.Sheet, FeatureOwner.Global, false)
        UiFeature("usage", FeatureSurface.Page, FeatureOwner.Account, false)
        UiFeature("limits", FeatureSurface.Page, FeatureOwner.Account, false)
        UiFeature("update", FeatureSurface.Inline, FeatureOwner.System, false)
        val settingsGroups = listOf(
          FeatureGroup("账户", listOf("usage"))
        )
        val legacyRoutes = listOf("home")
        '''
        navigation = '''
        val move: (String) -> Unit = { destination ->
          when (destination) {
            "threads" -> { openDrawer() }
          }
          if (page.id != destination) {}
        }
        SidebarSessions()
        '''
        return {
            "UiFeatureCatalog.kt": catalog,
            "AppNavigation.kt": navigation,
            "SettingsHub.kt": "AppUpdateCard()",
            "Screen.kt": "OutlinedButton(onClick = { save() })",
        }

    def test_registered_sheet_is_owned_by_its_contextual_overlay(self):
        report = audit_surface_hierarchy(
            self.fixture(), {"home", "threads", "usage", "limits"})
        self.assertEqual(5, report["featureCount"])
        self.assertEqual(1, report["settingsEntryCount"])
        self.assertEqual([], report["invalidHierarchy"])
        self.assertEqual([], report["missingSurfaceMappings"])
        self.assertEqual([], report["emptyActionHandlers"])
        fixture = self.fixture()
        fixture["UiFeatureCatalog.kt"] += '\nUiFeature("queue", FeatureSurface.Sheet, FeatureOwner.Global, true)'
        fixture["AppNavigation.kt"] = fixture["AppNavigation.kt"].replace(
            '"threads" -> { openDrawer() }', '"threads" -> { openDrawer() }\n"queue" -> { openSheet("queue") }')
        fixture["AppNavigation.kt"] += '\nif (activeSheet == "queue") ThreadQueueQuickSheet(vm)'
        valid = audit_surface_hierarchy(fixture, {"home", "threads", "usage", "limits", "queue"})
        self.assertEqual([], valid["missingSurfaceMappings"])
        fixture["AppNavigation.kt"] = fixture["AppNavigation.kt"].replace(
            'activeSheet == "queue"', 'activeSheet == "review"')
        invalid = audit_surface_hierarchy(fixture, {"home", "threads", "usage", "limits", "queue"})
        self.assertIn("queue:弹层入口或组件未接通", invalid["missingSurfaceMappings"])

    def test_duplicate_settings_and_empty_buttons_are_detected(self):
        fixture = self.fixture()
        fixture["UiFeatureCatalog.kt"] = fixture["UiFeatureCatalog.kt"].replace(
            'FeatureGroup("账户", listOf("usage"))',
            'FeatureGroup("账户", listOf("usage", "limits", "usage"))')
        fixture["Screen.kt"] = "OutlinedButton(onClick = {})"
        report = audit_surface_hierarchy(
            fixture, {"home", "threads", "usage", "limits"})
        self.assertTrue(report["invalidHierarchy"])
        self.assertIn("Screen.kt:1", report["emptyActionHandlers"])

    def test_sheet_audit_survives_route_transition_refactoring_and_checks_each_case(self):
        fixture = self.fixture()
        fixture["UiFeatureCatalog.kt"] += '\nUiFeature("search", FeatureSurface.Sheet, FeatureOwner.Global, false)'
        fixture["AppNavigation.kt"] = fixture["AppNavigation.kt"].replace(
            '"threads" -> { openDrawer() }',
            '"threads", "search" -> { openDrawer() }').replace(
            'if (page.id != destination) {}', 'if ((requestedPage ?: selected) != destination) {}')
        ids = {"home", "threads", "search", "usage", "limits"}
        self.assertEqual([], audit_surface_hierarchy(fixture, ids)["missingSurfaceMappings"])
        fixture["AppNavigation.kt"] = fixture["AppNavigation.kt"].replace(
            '"threads", "search" -> { openDrawer() }',
            '"threads" -> { openDrawer() }\n"search" -> { unrelated = true }')
        self.assertEqual(["search:弹层入口或组件未接通"],
                         audit_surface_hierarchy(fixture, ids)["missingSurfaceMappings"])

    def test_missing_sheet_dispatch_is_detected(self):
        fixture = self.fixture()
        fixture["AppNavigation.kt"] = fixture["AppNavigation.kt"].replace(
            "openDrawer()", "unrelated = true")
        report = audit_surface_hierarchy(
            fixture, {"home", "threads", "usage", "limits"})
        self.assertIn("threads:弹层入口或组件未接通",
                      report["missingSurfaceMappings"])


if __name__ == "__main__":
    unittest.main()
