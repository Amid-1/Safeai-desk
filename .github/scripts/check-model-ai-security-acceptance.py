#!/usr/bin/env python3
"""Fail closed on absent, skipped, failed or inconsistent Models/AI test evidence."""

import argparse
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET


MATRIX = Path(__file__).with_name("model-ai-security-acceptance.json")


def read_matrix():
    result = json.loads(MATRIX.read_text(encoding="utf-8"))
    if not result or any(not cls.startswith("ru.safeai.gateway.") for cls in result):
        raise ValueError("invalid mandatory test matrix")
    return result


def validate_report(path, cls, required):
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"missing regular report: {cls}")
    root = ET.parse(path).getroot()
    cases = root.findall("testcase")
    if (root.tag != "testsuite" or root.get("name") != cls or not cases
            or int(root.get("tests", "-1")) != len(cases)
            or any(root.get(key) != "0" for key in ("failures", "errors", "skipped"))):
        raise ValueError(f"invalid, failed or skipped suite: {cls}")
    names = []
    for case in cases:
        name = case.get("name")
        if (not name or case.get("classname") != cls
                or any(case.find(kind) is not None for kind in ("skipped", "failure", "error"))):
            raise ValueError(f"invalid, failed or skipped case: {cls}.{name}")
        names.append(name)
    if len(names) != len(set(names)):
        raise ValueError(f"duplicate test cases: {cls}")
    missing = set(required).difference(names)
    if missing:
        raise ValueError(f"missing exact cases: {cls}: {sorted(missing)}")
    return len(cases)


def check(backend, matrix):
    reports = backend / "target/surefire-reports"
    if not reports.is_dir() or reports.is_symlink():
        raise ValueError("missing regular Surefire report directory")
    count = 0
    for cls, required in sorted(matrix.items()):
        path = reports / f"TEST-{cls}.xml"
        count += validate_report(path, cls, required)
    print(f"PASS: {len(matrix)} mandatory suites; {count} executed cases; "
          f"{sum(map(len, matrix.values()))} exact required methods; zero skips")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backend", nargs="?", type=Path)
    parser.add_argument("--list-classes", action="store_true")
    args = parser.parse_args()
    matrix = read_matrix()
    if args.list_classes:
        print(",".join(sorted(matrix)))
    elif args.backend is not None:
        check(args.backend, matrix)
    else:
        parser.error("specify backend directory or --list-classes")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, ET.ParseError) as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        sys.exit(1)
