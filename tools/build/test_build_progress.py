import sys
import unittest
from build_progress import run

class BuildProgressTests(unittest.TestCase):
    def test_success(self):
        self.assertEqual(run([sys.executable,"-c","print('completed')"],idle=2,maximum=3),0)
    def test_failure_propagates(self):
        self.assertEqual(run([sys.executable,"-c","raise SystemExit(7)"],idle=2,maximum=3),7)
    def test_no_output_stops_process(self):
        self.assertEqual(run([sys.executable,"-c","import time;time.sleep(30)"],idle=.2,maximum=3),124)
