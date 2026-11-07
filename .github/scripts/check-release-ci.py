#!/usr/bin/env python3
# SafeAI-Desk/.github/scripts/check-release-ci.py
"""Fail-closed проверка CI для ТОГО ЖЕ SHA, который собирается к публикации."""
import argparse
import json
from pathlib import Path

REQUIRED = {
    "Java 21, Maven, архитектура и интеграционные тесты",
    "Usage PostgreSQL / performance SLA",
    "Архитектура, ESLint, тесты и сборка",
    "Dockerfiles, Compose и image scan",
    "CodeQL / java-kotlin",
    "CodeQL / javascript-typescript",
    "Trivy / зависимости и секреты",
    "qodana",
    "GitHub Actions / actionlint",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report", type=Path)
    ap.add_argument("sha")
    ns = ap.parse_args()
    data = json.loads(ns.report.read_text(encoding="utf-8"))
    records = data.get("check_runs")
    if not isinstance(records, list):
        raise SystemExit("ОШИБКА: отсутствует check_runs в ответе GitHub API")
    if data.get("total_count", len(records)) > len(records):
        raise SystemExit("ОТКАЗ: GitHub API не вернул полный список check runs")
    runs = {}
    for record in records:
        name = record.get("name")
        if name not in REQUIRED or record.get("head_sha") != ns.sha:
            continue
        # На каждый job GitHub может хранить несколько rerun/check_run.
        if name not in runs or record.get("id", 0) > runs[name].get("id", 0):
            runs[name] = record
    missing = REQUIRED - runs.keys()
    bad = [(name, run.get("status"), run.get("conclusion"))
           for name, run in sorted(runs.items())
           if run.get("status") != "completed" or run.get("conclusion") != "success"]
    if missing or bad:
        raise SystemExit(
            f"ОТКАЗ: CI для {ns.sha} не подтверждён. "
            f"Отсутствуют: {sorted(missing)}; неуспешны: {bad}"
        )
    print(f"РЕЛИЗНЫЙ ШЛЮЗ OK: {len(REQUIRED)} успешных проверок, SHA={ns.sha}")


if __name__ == "__main__":
    main()
