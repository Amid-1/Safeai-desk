#!/usr/bin/env python3
"""Проверка JaCoCo coverage baseline по каждому backend-модулю."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from xml.etree import ElementTree

MODULES = (
    "ai", "audit", "auth", "chat", "common", "identity",
    "knowledge", "model", "organization", "ratelimit", "usage", "user",
)
KINDS = ("LINE", "BRANCH")
BASELINE_VERSION = 1


def coverage_report(path: Path) -> dict:
    if not path.is_file():
        raise ValueError(f"Отсутствует JaCoCo XML: {path}")
    root = ElementTree.parse(path).getroot()
    totals = {module: {kind: [0, 0] for kind in KINDS} for module in MODULES}
    unknown = set()

    for package in root.findall("package"):
        parts = package.attrib.get("name", "").split("/")
        if len(parts) < 4 or parts[:3] != ["ru", "safeai", "gateway"]:
            continue
        module = parts[3]
        if module not in totals:
            unknown.add(module)
            continue
        for counter in package.findall("counter"):
            kind = counter.get("type")
            if kind in KINDS:
                totals[module][kind][0] += int(counter.get("covered", "0"))
                totals[module][kind][1] += int(counter.get("missed", "0"))

    if unknown:
        raise ValueError(
            "Новые runtime-модули не включены в coverage policy: "
            + ", ".join(sorted(unknown))
        )

    result = {}
    for module, counters in totals.items():
        result[module] = {}
        for kind, (covered, missed) in counters.items():
            count = covered + missed
            result[module][kind.lower()] = (
                round(covered / count, 6) if count else None
            )
            result[module][kind.lower() + "_counts"] = [covered, missed]

    aggregate = {kind: [0, 0] for kind in KINDS}
    for counters in totals.values():
        for kind in KINDS:
            aggregate[kind][0] += counters[kind][0]
            aggregate[kind][1] += counters[kind][1]

    result["_total"] = {}
    for kind, (covered, missed) in aggregate.items():
        count = covered + missed
        result["_total"][kind.lower()] = (
            round(covered / count, 6) if count else None
        )
        result["_total"][kind.lower() + "_counts"] = [covered, missed]

    if not any(result[module]["line"] is not None for module in MODULES):
        raise ValueError("В JaCoCo XML нет покрываемых пакетов ru/safeai/gateway")
    return result


def enforce(current: dict, baseline: dict, tolerance: float) -> list[str]:
    if baseline.get("_format") != BASELINE_VERSION:
        raise ValueError("Неизвестный формат coverage baseline")
    baseline_modules = baseline.get("modules")
    if not isinstance(baseline_modules, dict):
        raise ValueError("В baseline отсутствует объект modules")

    errors = []
    for module in (*MODULES, "_total"):
        previous = baseline_modules.get(module)
        if not isinstance(previous, dict):
            errors.append(f"{module}: нет базовой линии")
            continue
        for kind in ("line", "branch"):
            old = previous.get(kind)
            new = current[module][kind]
            if old is None:
                if new is not None and new == 0:
                    errors.append(f"{module}.{kind}: появился полностью непокрытый код")
                continue
            if new is None:
                errors.append(f"{module}.{kind}: измерение исчезло из отчёта")
            elif new + tolerance < old:
                errors.append(
                    f"{module}.{kind}: {new:.1%} ниже baseline {old:.1%} "
                    f"(допуск {tolerance:.1%})"
                )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--capture", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument(
        "--xml", type=Path,
        default=Path("backend/target/site/jacoco/jacoco.xml"),
    )
    parser.add_argument(
        "--baseline", type=Path,
        default=Path("backend/coverage-baseline.json"),
    )
    parser.add_argument("--tolerance", type=float, default=0.01)
    args = parser.parse_args()

    try:
        if not 0 <= args.tolerance <= 0.05:
            raise ValueError("Допуск должен быть от 0 до 5%; по умолчанию 1%")
        current = coverage_report(args.xml)
        for name in (*MODULES, "_total"):
            item = current[name]
            line = "нет измерения" if item["line"] is None else f'{item["line"]:.1%}'
            branch = "нет измерения" if item["branch"] is None else f'{item["branch"]:.1%}'
            print(f"{name:15} LINE={line:>14} BRANCH={branch:>14}")

        if args.capture:
            if args.baseline.exists():
                raise ValueError(
                    f"Baseline уже существует: {args.baseline}. "
                    "Обновляй его только после отдельного review."
                )
            args.baseline.parent.mkdir(parents=True, exist_ok=True)
            args.baseline.write_text(
                json.dumps(
                    {"_format": BASELINE_VERSION, "modules": current},
                    indent=2, ensure_ascii=False,
                ) + "\n",
                encoding="utf-8",
            )
            print(f"BASELINE CREATED: {args.baseline}; проверь и добавь отдельным коммитом")
        else:
            if not args.baseline.is_file():
                raise ValueError(f"Нет обязательного baseline: {args.baseline}")
            baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
            errors = enforce(current, baseline, args.tolerance)
            if errors:
                raise ValueError("Покрытие регрессировало:\n - " + "\n - ".join(errors))
            print("COVERAGE OK: baseline всех модулей сохранён")
        return 0
    except (OSError, ValueError, ElementTree.ParseError, json.JSONDecodeError) as exc:
        print(f"COVERAGE FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
