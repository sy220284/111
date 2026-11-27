import sys
import tempfile
import unittest
from pathlib import Path
from transfer_progress import download, TransferUnavailable

class TransferTests(unittest.TestCase):
    def test_exact_download(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)/"artifact"
            download("fixture", out, 3, interval=.05, maximum=3,
                     command=[sys.executable,"-c","import sys;sys.stdout.buffer.write(b'abc')"])
            self.assertEqual(out.read_bytes(), b"abc")
    def test_stall_terminates_child_and_removes_partial_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)/"artifact"
            with self.assertRaises(TransferUnavailable):
                download("fixture", out, 3, stall=.1, maximum=2, interval=.05, attempts=1,
                         command=[sys.executable,"-c","import time;time.sleep(30)"])
            self.assertFalse(out.exists())
    def test_permission_failure_is_not_retried(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)/"artifact"
            with self.assertRaisesRegex(RuntimeError,"authorization"):
                download("fixture", out, 3, interval=.05, maximum=3,
                         command=[sys.executable,"-c","import sys;print('HTTP 403',file=sys.stderr);sys.exit(1)"])
            self.assertFalse(out.exists())
