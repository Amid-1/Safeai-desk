#!/usr/bin/env python3
"""Negative tests: missing coverage, wrong counters, group/branch regressions."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "frontend_coverage_gate", HERE / "check-frontend-coverage.py")
assert SPEC is not None and SPEC.loader is not None
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


def metric(covered: int, total: int) -> dict:
    return {"covered": covered, "total": total, "pct": 100 * covered / total if total else 100}


def coverage(items: dict[str, tuple[int, int, int, int]]) -> dict:
    document = {}
    line_covered = line_total = branch_covered = branch_total = 0
    for path, (lc, lt, bc, bt) in items.items():
        document[f"/repo/frontend/src/features/{path}/Main.tsx"] = {
            "lines": metric(lc, lt), "branches": metric(bc, bt)}
        line_covered += lc
        line_total += lt
        branch_covered += bc
        branch_total += bt
    document["total"] = {"lines": metric(line_covered, line_total),
                         "branches": metric(branch_covered, branch_total)}
    return document


class GateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "coverage-summary.json"
        self.items = {"usage": (55, 100, 58, 100),
                      "auth": (80, 100, 70, 100)}
        self.write(self.items)
        self.baseline = GATE.capture(self.path)

    def write(self, items):
        self.path.write_text(json.dumps(coverage(items)), encoding="utf-8")

    def errors(self, items):
        self.write(items)
        return GATE.enforce(GATE.capture(self.path), self.baseline)

    def test_dashboard_shows_real_baselines(self):
        target = Path(self.tmp.name) / "frontend-baseline.json"
        target.write_text(json.dumps(self.baseline), encoding="utf-8")
        report = GATE.load_report_module()
        result = report.frontend_stats(self.path, target)
        usage = next(row for row in result["rows"] if row["name"] == "usage")
        self.assertEqual(usage["baseline_line"], 0.55)
        self.assertEqual(usage["baseline_branch"], 0.58)
        self.assertIn("Baseline (ветви)", report.markdown(result))
        self.assertIn("Δ ветвей", report.html_page(result))

    def test_same_coverage_passes(self):
        self.assertEqual(self.errors(self.items), [])

    def test_slight_decline_within_one_point_passes(self):
        items = {"usage": (54, 100, 57, 100), "auth": (80, 100, 70, 100)}
        self.assertEqual(self.errors(items), [])

    def test_line_regression_fails(self):
        self.assertIn("usage.line", " ".join(self.errors({
            "usage": (38, 100, 58, 100), "auth": (80, 100, 70, 100)})))

    def test_branch_regression_fails(self):
        self.assertIn("usage.branch", " ".join(self.errors({
            "usage": (55, 100, 50, 100), "auth": (80, 100, 70, 100)})))

    def test_overall_regression_fails(self):
        self.assertIn("_total", " ".join(self.errors({
            "usage": (40, 100, 40, 100), "auth": (70, 100, 60, 100)})))

    def test_group_disappears_fails(self):
        self.assertIn("disappeared", " ".join(self.errors({
            "usage": (55, 100, 58, 100)})))

    def test_new_group_fails(self):
        items = dict(self.items, newfeature=(10, 10, 8, 8))
        self.assertIn("New groups", " ".join(self.errors(items)))

    def test_reduced_instrumentation_fails(self):
        items = {"usage": (22, 40, 23, 40), "auth": (80, 100, 70, 100)}
        self.assertIn("measured code dropped", " ".join(self.errors(items)))

    def test_corrupted_baseline_fails(self):
        self.baseline["modules"]["usage"]["line"] = 0.99
        with self.assertRaisesRegex(ValueError, "ratio/count mismatch"):
            GATE.enforce(GATE.capture(self.path), self.baseline)

    def test_missing_baseline_group_fails(self):
        del self.baseline["modules"]["usage"]
        self.assertIn("New groups", " ".join(self.errors(self.items)))

    def test_missing_summary_fails(self):
        with self.assertRaisesRegex(ValueError, "missing"):
            GATE.capture(self.path.with_name("not-found.json"))

    def test_missing_file_from_total_fails(self):
        data = coverage(self.items)
        del data["/repo/frontend/src/features/usage/Main.tsx"]
        self.path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Inconsistent|inconsistent"):
            GATE.capture(self.path)

    def test_invalid_fractional_counters_fail(self):
        data = coverage(self.items)
        data["total"]["lines"]["covered"] = 135.5
        self.path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Invalid"):
            GATE.capture(self.path)

    def test_empty_group_stays_null(self):
        items = dict(self.items, empty=(0, 0, 0, 0))
        self.write(items)
        before = GATE.capture(self.path)
        self.assertIsNone(before["modules"]["empty"]["line"])
        self.assertEqual(GATE.enforce(before, before), [])
        items["empty"] = (0, 4, 0, 4)
        self.write(items)
        self.assertIn("newly measurable", " ".join(
            GATE.enforce(GATE.capture(self.path), before)))


if __name__ == "__main__":
    unittest.main()
