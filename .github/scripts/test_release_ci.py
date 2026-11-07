#!/usr/bin/env python3
# SafeAI-Desk/.github/scripts/test_release_ci.py
"""Регрессии release-gate: нельзя считать отсутствие или failure успехом."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).with_name("check-release-ci.py")
sys.path.insert(0, str(Path(__file__).parent))


class ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("check_release_ci", CHECKER)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.sha = "a" * 40

    def invoke(self, runs):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp, "checks.json")
            output.write_text(json.dumps({"check_runs": runs}), encoding="utf-8")
            return subprocess.run([sys.executable, str(CHECKER), str(output), self.sha],
                                  capture_output=True, text=True)

    def valid(self):
        return [dict(id=i + 1, name=name, head_sha=self.sha, status="completed", conclusion="success")
                for i, name in enumerate(sorted(self.module.REQUIRED))]

    def test_all_required_checks_pass(self):
        self.assertEqual(self.invoke(self.valid()).returncode, 0)

    def test_missing_check_blocks_release(self):
        self.assertNotEqual(self.invoke(self.valid()[:-1]).returncode, 0)

    def test_failure_blocks_release(self):
        r = self.valid()
        r[0]["conclusion"] = "failure"
        self.assertNotEqual(self.invoke(r).returncode, 0)

    def test_in_progress_blocks_release(self):
        r = self.valid()
        r[0]["status"] = "in_progress"
        r[0]["conclusion"] = None
        self.assertNotEqual(self.invoke(r).returncode, 0)

    def test_old_commit_cannot_be_substituted(self):
        r = self.valid()
        r[0]["head_sha"] = "b" * 40
        self.assertNotEqual(self.invoke(r).returncode, 0)

    def test_latest_failure_overrides_prior_success(self):
        r = self.valid()
        failed = dict(r[0], id=999, conclusion="failure")
        r.append(failed)
        self.assertNotEqual(self.invoke(r).returncode, 0)


if __name__ == "__main__":
    unittest.main()
