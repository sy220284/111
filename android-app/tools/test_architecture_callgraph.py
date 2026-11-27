import unittest
from pathlib import Path
from types import SimpleNamespace
from architecture_callgraph import scan

class CallGraphTests(unittest.TestCase):
    def test_alias_callable_reference_lambda_and_cycle(self):
        root=Path("/repo")
        data=scan(SimpleNamespace(root=root, production={
            root/"A.kt":"package p\nimport q.beta as renamed\nfun alpha() { ::renamed }",
            root/"B.kt":"package q\nimport p.alpha\nfun beta() { alpha { } }",
            root/"C.kt":'package p\nfun unused() { println("alpha()") } // beta()'
        }))
        self.assertEqual(["B.kt"],data["files"]["A.kt"]["call_candidates"])
        self.assertEqual(["A.kt"],data["files"]["B.kt"]["call_candidates"])
        self.assertEqual([["A.kt","B.kt"]],data["cycle_candidates"])
        self.assertIn("C.kt",data["unreferenced_candidates"])
        self.assertEqual([],data["files"]["C.kt"]["call_candidates"])

    def test_ambiguous_symbols_are_not_guessed(self):
        root=Path("/repo")
        result=scan(SimpleNamespace(root=root,production={
            root/"A.kt":"package p\nfun a(){ duplicate() }",
            root/"B.kt":"package p\nfun duplicate(){}",
            root/"C.kt":"package p\nfun duplicate(){}"}))
        self.assertEqual([],result["files"]["A.kt"]["call_candidates"])
