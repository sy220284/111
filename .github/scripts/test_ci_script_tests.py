import unittest
from pathlib import Path
from run_ci_script_tests import suites

class SuiteOwnershipTests(unittest.TestCase):
    def test_every_script_has_one_owner(self):
        root=Path(__file__).resolve().parent
        a,b=set(suites(root,"plan")),set(suites(root,"preflight"))
        self.assertFalse(a & b)
        self.assertEqual({p.name for p in root.glob("test_*.py")},a | b)
