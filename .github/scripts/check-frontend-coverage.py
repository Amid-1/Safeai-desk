#!/usr/bin/env python3
"""Fail-closed per-group Vitest coverage gate. Baseline is never updated in CI."""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
from pathlib import Path
import sys

FORMAT = 1
TOLERANCE = 0.01  # One percentage point, matching the backend gate.
MIN_TOTAL_DENOMINATOR_RATIO = 0.90
MIN_GROUP_DENOMINATOR_RATIO = 0.70
KINDS = ("line", "branch")


def load_report_module():
    path = Path(__file__).with_name("quality-report.py")
    spec = importlib.util.spec_from_file_location("safeai_quality_report", path)
    if not spec or not spec.loader:
        raise ValueError("Cannot load shared Vitest grouping logic")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def checked_counter(item: dict, name: str) -> tuple[int, int]:
    metric = item.get(name)
    if not isinstance(metric, dict):
        raise ValueError(f"Missing {name} counter")
    covered = metric.get("covered")
    total = metric.get("total")
    if (type(covered) is not int or type(total) is not int or
            covered < 0 or total < covered):
        raise ValueError(f"Invalid {name} counter")
    return covered, total


def capture(summary_path: Path) -> dict:
    """Return validated exact ratios and covered/missed counts from V8 JSON."""
    if not summary_path.is_file():
        raise ValueError(f"Vitest coverage file is missing: {summary_path}")
    raw = json.loads(summary_path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or len(raw) < 2:
        raise ValueError("Empty or invalid Vitest coverage-summary.json")
    if not isinstance(raw.get("total"), dict):
        raise ValueError("Missing total Vitest coverage")

    file_sums = {"lines": [0, 0], "branches": [0, 0]}
    paths = set()
    for path, entry in raw.items():
        if path == "total":
            continue
        if not isinstance(path, str) or not isinstance(entry, dict):
            raise ValueError("Invalid per-file Vitest coverage")
        normalized = path.replace("\\", "/")
        if normalized in paths or "/src/" not in normalized:
            raise ValueError(f"Duplicate or out-of-scope coverage path: {path}")
        paths.add(normalized)
        for key in file_sums:
            covered, total = checked_counter(entry, key)
            file_sums[key][0] += covered
            file_sums[key][1] += total

    for key in file_sums:
        if file_sums[key] != list(checked_counter(raw["total"], key)):
            raise ValueError(f"Incomplete/inconsistent Vitest {key} coverage")
    if file_sums["lines"][1] == 0:
        raise ValueError("No measurable Vitest lines")

    report = load_report_module().frontend_stats(summary_path)
    modules = {}
    for row in report["rows"]:
        module = {}
        for kind, field in (("line", "line_count"),
                            ("branch", "branch_count")):
            total = row[field]
            fraction = row[kind]
            if fraction is None:
                if total != 0:
                    raise ValueError(f"Missing {kind} ratio: {row['name']}")
                covered = 0
            else:
                covered = round(fraction * total)
                if abs(fraction - covered / total) > 1e-9:
                    raise ValueError(f"Inconsistent {kind} ratio: {row['name']}")
            module[kind] = (covered / total) if total else None
            module[kind + "_counts"] = [covered, total - covered]
        modules[row["name"]] = module

    overall = {}
    for kind, key in (("line", "lines"), ("branch", "branches")):
        covered, total = file_sums[key]
        overall[kind] = covered / total if total else None
        overall[kind + "_counts"] = [covered, total - covered]
    modules["_total"] = overall
    return {"_format": FORMAT, "modules": modules}


def validate(baseline: dict) -> dict:
    if not isinstance(baseline, dict) or baseline.get("_format") != FORMAT:
        raise ValueError("Unsupported frontend coverage baseline format")
    modules = baseline.get("modules")
    if not isinstance(modules, dict) or "_total" not in modules:
        raise ValueError("Missing frontend coverage baseline modules")
    for name, item in modules.items():
        if not isinstance(item, dict):
            raise ValueError(f"Invalid baseline group: {name}")
        for kind in KINDS:
            counts = item.get(kind + "_counts")
            value = item.get(kind)
            if (not isinstance(counts, list) or len(counts) != 2 or
                    any(type(x) is not int or x < 0 for x in counts)):
                raise ValueError(f"Invalid baseline counts: {name}.{kind}")
            total = sum(counts)
            if total == 0:
                if value is not None:
                    raise ValueError(f"Empty baseline must be null: {name}.{kind}")
            elif (type(value) not in (float, int) or not math.isfinite(value)
                  or abs(value - counts[0] / total) > 1e-9):
                raise ValueError(f"Baseline ratio/count mismatch: {name}.{kind}")
    return modules


def enforce(current: dict, baseline: dict) -> list[str]:
    before = validate(baseline)
    now = validate(current)
    errors = []
    missing = sorted(before.keys() - now.keys())
    added = sorted(now.keys() - before.keys())
    if missing:
        errors.append(f"Groups disappeared from coverage: {missing}")
    if added:
        errors.append(f"New groups require reviewed baseline: {added}")
    for name in sorted(before.keys() & now.keys()):
        old = before[name]
        new = now[name]
        for kind in KINDS:
            previous = old[kind]
            actual = new[kind]
            old_total = sum(old[kind + "_counts"])
            new_total = sum(new[kind + "_counts"])
            threshold = (MIN_TOTAL_DENOMINATOR_RATIO if name == "_total"
                         else MIN_GROUP_DENOMINATOR_RATIO)
            if old_total and new_total < old_total * threshold:
                errors.append(f"{name}.{kind}: measured code dropped from "
                              f"{old_total} to {new_total} (minimum {threshold:.0%})")
            if previous is None:
                if actual is not None:
                    errors.append(f"{name}.{kind}: newly measurable; review baseline")
            elif actual is None:
                errors.append(f"{name}.{kind}: measurement disappeared")
            elif actual + TOLERANCE < previous - 1e-12:
                errors.append(f"{name}.{kind}: {actual:.2%} below baseline "
                              f"{previous:.2%} by more than {TOLERANCE:.0%} point")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", type=Path, default=Path(
        "frontend/coverage/coverage-summary.json"))
    parser.add_argument("--baseline", type=Path, default=Path(
        "frontend/coverage-baseline.json"))
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--capture", action="store_true")
    args = parser.parse_args()
    try:
        current = capture(args.summary)
        if args.capture:
            if args.baseline.exists():
                raise ValueError("Baseline exists; manual review required before replacement")
            args.baseline.write_text(json.dumps(current, ensure_ascii=False,
                                                indent=2) + "\n", encoding="utf-8")
            print(f"CAPTURED: {args.baseline}; review and commit the new baseline")
        else:
            if not args.baseline.is_file():
                raise ValueError("Missing mandatory frontend coverage baseline")
            baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
            errors = enforce(current, baseline)
            if errors:
                raise ValueError("Vitest coverage regressed:\n - " + "\n - ".join(errors))
            print("FRONTEND COVERAGE OK: overall and all groups, lines and branches")
        return 0
    except (ValueError, OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
        print(f"FRONTEND COVERAGE FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
