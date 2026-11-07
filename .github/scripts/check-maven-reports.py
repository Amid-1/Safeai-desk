#!/usr/bin/env python3
# SafeAI-Desk/.github/scripts/check-maven-reports.py
"""Блокирующая сверка Surefire XML: нельзя тихо пропустить тесты или результаты."""
import argparse
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

BASELINE = {
    ("ru.safeai.gateway.usage.repository.UsageExplainAnalyzePerformanceIntegrationTest",
     "explainAnalyzeCoversGlobalModelOrganizationAndUserShapes"),
    ("ru.safeai.gateway.usage.repository.UsagePerformanceIntegrationTest",
     "concurrentAdmissionControlProtectsDatabasePool"),
    ("ru.safeai.gateway.usage.repository.UsagePerformanceIntegrationTest",
     "rollupBackedReportsFor366DaysStayWithinConfiguredSla"),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["standard", "performance"])
    parser.add_argument("--reports", type=Path, default=Path("backend/target/surefire-reports"))
    args = parser.parse_args()
    files = sorted(args.reports.glob("TEST-*.xml"))
    if not files:
        raise SystemExit("ОШИБКА: отсутствуют Surefire XML-отчёты")
    cases = []
    for report in files:
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as error:
            raise SystemExit(f"ОШИБКА: повреждён XML {report}: {error}") from error
        for c in root.iter("testcase"):
            key = (c.attrib.get("classname"), c.attrib.get("name"))
            cases.append((key, c.find("skipped"), c.find("failure"), c.find("error")))
    if not cases:
        raise SystemExit("ОШИБКА: отчёты не содержат выполненных тестов")
    failed = [key for key, _, failure, error in cases if failure is not None or error is not None]
    skipped = {key for key, skip, _, _ in cases if skip is not None}
    executed = {key for key, skip, _, _ in cases if skip is None}
    if failed:
        raise SystemExit(f"ОШИБКА: есть падения тестов: {failed[:20]}")
    if args.mode == "standard":
        if len(cases) < 2000:
            raise SystemExit(f"ОШИБКА: регрессия количества тестов: {len(cases)} < 2000")
        if skipped != BASELINE:
            raise SystemExit(f"ОШИБКА: набор пропусков отличается от 3 разрешённых performance-тестов: {sorted(skipped ^ BASELINE)}")
    else:
        if skipped:
            raise SystemExit(f"ОШИБКА: пропуски в performance suite: {sorted(skipped)}")
        if not BASELINE.issubset(executed):
            raise SystemExit(f"ОШИБКА: не запущены обязательные performance-тесты: {sorted(BASELINE - executed)}")
    print(f"ПРОВЕРКА OK: режим={args.mode}, отчётов={len(files)}, тестов={len(cases)}, пропусков={len(skipped)}")


if __name__ == "__main__":
    main()
