import unittest
from check_architecture_boundaries import check, OWNERS, OWNED_REQUEST_PATHS
class ArchitectureTests(unittest.TestCase):
    def test_core_never_depends_on_compose_or_navigation(self):
        base=dict.fromkeys(set(OWNERS)|OWNED_REQUEST_PATHS,"import java.io.File\n")
        self.assertEqual([],check(base))
        for name in OWNERS:
            self.assertEqual(1,len(check(base|{name:"import androidx.compose.runtime.State"})))
            self.assertEqual(1,len(check(base|{name:"fun x()=androidx.navigation.NavController()"})))
        self.assertEqual([],check(base|{"CodexConnection.kt":'// androidx.compose.runtime.State\nval sample="androidx.navigation.X"'}))
        del base["CodexConnection.kt"]
        self.assertEqual(1,len(check(base)))

    def test_converged_request_paths_cannot_bypass_owner(self):
        base=dict.fromkeys(set(OWNERS)|OWNED_REQUEST_PATHS,"")
        for name in OWNED_REQUEST_PATHS:
            self.assertEqual(1,len(check(base|{name:"vm.bridge . request(method, params)"})))
            self.assertEqual([],check(base|{name:"owner.request(vm, method, params)"}))
            self.assertEqual([],check(base|{name:'// bridge.request()\nval sample="bridge.request()"'}))
