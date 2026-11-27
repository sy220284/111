import sys
import unittest
from build_progress import choose_jobs, cpu_delta, progress_line, run


class BuildProgressTests(unittest.TestCase):
    def test_success(self):
        self.assertEqual(run([sys.executable, '-c', "print('completed')"], idle=2, maximum=3), 0)

    def test_failure_propagates(self):
        self.assertEqual(run([sys.executable, '-c', 'raise SystemExit(7)'], idle=2, maximum=3), 7)

    def test_no_activity_stops_process(self):
        self.assertEqual(run([sys.executable, '-c', 'import time;time.sleep(30)'], idle=.3, maximum=3), 124)

    def test_silent_busy_process_is_not_mistaken_for_stall(self):
        code = 'import time; end=time.monotonic()+.8\nwhile time.monotonic()<end: pass'
        self.assertEqual(run([sys.executable, '-c', code], idle=.3, maximum=3), 0)

    def test_hard_deadline_even_with_activity(self):
        self.assertEqual(run([sys.executable, '-c', 'while True: pass'], idle=2, maximum=.4), 124)

    def test_job_budget(self):
        self.assertEqual(choose_jobs('v8', 2, 7000), 2)
        self.assertEqual(choose_jobs('v8', 8, 16000), 6)
        self.assertEqual(choose_jobs('rust', 8, 16000), 4)
        self.assertEqual(choose_jobs('rust', 16, 1024), 1)
        self.assertEqual(choose_jobs('v8', 2, 32000), 2)

    def test_cpu_and_progress(self):
        self.assertEqual(cpu_delta({('1','10'): 10}, {('1','10'): 12, ('2','11'): 3}), 5)
        self.assertEqual(progress_line(b'[v8 152.2.0] [3877/4484] CXX foo', None), (3877,4484))
        self.assertEqual(progress_line(b'[7/0]', (3,8)), (3,8))
        self.assertEqual(progress_line(b'no counter', (3,8)), (3,8))
        self.assertEqual(progress_line(b'other message [8/9]', (3,8)), (3,8))
        self.assertEqual(progress_line(b'[8/9] CXX foo', None), (8,9))
