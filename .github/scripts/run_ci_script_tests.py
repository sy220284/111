#!/usr/bin/env python3
"""One owner per repository Python test suite during unified CI."""
import argparse
import sys
import unittest
from pathlib import Path

PLAN = {"test_ci_policy.py", "test_ci_gate.py", "test_ci_scope.py", "test_native_reuse.py",
        "test_retain_check_artifacts.py", "test_repository_steward.py", "test_ci_reuse.py",
        "test_ci_script_tests.py", "test_ci_queue_policy.py"}

def suites(directory, phase):
    files = {p.name for p in directory.glob("test_*.py")}
    if not PLAN <= files:
        raise ValueError("Missing mandatory CI planning suites: "+str(sorted(PLAN-files)))
    return sorted(PLAN if phase == "plan" else files-PLAN)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("phase", choices=("plan","preflight"))
    args=parser.parse_args()
    directory=Path(__file__).resolve().parent
    result=unittest.TestSuite()
    for filename in suites(directory,args.phase):
        result.addTests(unittest.defaultTestLoader.discover(str(directory),pattern=filename))
    return int(not unittest.TextTestRunner(verbosity=1).run(result).wasSuccessful())

if __name__=="__main__":
    sys.exit(main())
