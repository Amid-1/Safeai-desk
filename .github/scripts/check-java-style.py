#!/usr/bin/env python3
"""Incremental Java hygiene gate with immutable reviewed legacy exceptions.

Checks UTF-8, BOM, CRLF, tab characters, trailing whitespace and final newline.
This is NOT Google Java Format or a full Checkstyle ruleset.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

VERSION = 1


def issues(repo: Path) -> dict[str, dict[str, int]]:
    root = repo / "backend/src"
    if not root.is_dir():
        raise ValueError("Missing backend/src")
    result: dict[str, dict[str, int]] = {}
    sources = sorted(root.rglob("*.java"))
    if not sources:
        raise ValueError("No Java sources")
    for file in sources:
        data = file.read_bytes()
        path = file.relative_to(repo).as_posix()
        counts: Counter[str] = Counter()
        try:
            data.decode("utf-8")
        except UnicodeDecodeError as err:
            counts[f"invalid-utf8:{err.start}"] += 1
        if data.startswith(b"\xef\xbb\xbf"):
            counts["utf8-bom"] += 1
        if b"\r" in data:
            counts["carriage-return"] += 1
        if data and not data.endswith(b"\n"):
            counts["no-final-newline"] += 1
        for line in data.splitlines():
            if b"\t" in line:
                counts["tab:" + hashlib.sha256(line).hexdigest()[:20]] += 1
            if line.rstrip(b" \t") != line:
                counts["trailing-space:" + hashlib.sha256(line).hexdigest()[:20]] += 1
        if counts:
            result[path] = dict(sorted(counts.items()))
    return result


def check(current: dict, baseline: dict) -> list[str]:
    if baseline.get("_format") != VERSION or not isinstance(baseline.get("issues"), dict):
        raise ValueError("Invalid Java style baseline")
    errors = []
    previous = baseline["issues"]
    for path, entries in sorted(current.items()):
        allowed = previous.get(path, {})
        for key, count in sorted(entries.items()):
            if count > allowed.get(key, 0):
                errors.append(f"{path}: {key} ({count} > baseline {allowed.get(key, 0)})")
    return errors


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--repo", type=Path, default=Path("."))
    p.add_argument("--baseline", type=Path, default=Path(".github/style/java-hygiene-baseline.json"))
    p.add_argument("--capture", action="store_true")
    ns = p.parse_args()
    root = ns.repo.resolve()
    baseline_path = ns.baseline if ns.baseline.is_absolute() else root / ns.baseline
    try:
        current = issues(root)
        if ns.capture:
            if baseline_path.exists():
                raise ValueError("Baseline exists: do not silently refresh existing exceptions")
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_text(json.dumps({"_format": VERSION, "issues": current}, indent=2,
                                                ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
            print(f"BASELINE CREATED: {baseline_path} (review this file before commit)")
        else:
            baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
            errors = check(current, baseline)
            if errors:
                raise ValueError("Java style regressions:\n - " + "\n - ".join(errors[:30]))
            print(f"JAVA STYLE OK: no new hygiene violations; legacy files with exceptions: {len(baseline['issues'])}")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as err:
        print(f"JAVA STYLE FAILED: {err}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
