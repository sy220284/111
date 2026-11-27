import unittest
from build_recipe import normalize, job_recipe

class RecipeTests(unittest.TestCase):
    def test_scheduling_only(self):
        a = "jobs:\n  build:\n    concurrency:\n      group: old\n      queue: max\n    timeout-minutes: 30\n    runs-on: ubuntu-24.04\n    steps:\n      - run: cargo build"
        b = a.replace("old","new").replace("30","60").replace("queue: max","cancel-in-progress: true")
        self.assertEqual(normalize(a),normalize(b))
        self.assertNotEqual(normalize(a),normalize(a.replace("cargo build","cargo test")))
        self.assertNotEqual(normalize(a),normalize(a.replace("ubuntu-24.04","ubuntu-22.04")))

    def test_group_recipe_ignores_other_jobs_and_preserves_shell_content(self):
        w = "jobs:\n  android:\n    runs-on: ubuntu-24.04\n    steps:\n      - run: |\n          timeout-minutes: 30\n          compile\n  rust:\n    steps:\n      - run: cargo fetch"
        self.assertEqual(job_recipe(w,"android"),job_recipe(w.replace("cargo fetch","cargo build"),"android"))
        self.assertNotEqual(job_recipe(w,"android"),job_recipe(w.replace("timeout-minutes: 30","timeout-minutes: 60"),"android"))
