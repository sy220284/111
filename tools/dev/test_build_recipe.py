import unittest
from build_recipe import normalize

class RecipeTests(unittest.TestCase):
    def test_scheduling_only(self):
        a = "jobs:\n  build:\n    concurrency:\n      group: old\n      queue: max\n    timeout-minutes: 30\n    runs-on: ubuntu-24.04\n    steps:\n      - run: cargo build"
        b = a.replace("old","new").replace("30","60").replace("queue: max","cancel-in-progress: true")
        self.assertEqual(normalize(a),normalize(b))
        self.assertNotEqual(normalize(a),normalize(a.replace("cargo build","cargo test")))
        self.assertNotEqual(normalize(a),normalize(a.replace("ubuntu-24.04","ubuntu-22.04")))
