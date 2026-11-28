#!/usr/bin/env python3
"""Проверка синхронности публичных кодов аудита (не доказательство доставки)."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

JAVA = Path("backend/src/main/java/ru/safeai/gateway/audit/AuditEventType.java")
FRONT = Path("frontend/src/features/audit/constants/auditEvents.ts")
MIGRATIONS = Path("backend/src/main/resources/db/migration")
CODE = r"[A-Z][A-Z0-9_]+"


def unique(values: list[str], source: str) -> set[str]:
    if not values or len(values) != len(set(values)):
        raise ValueError(f"{source}: пустой справочник или повтор кода")
    return set(values)


def validate(repo: Path) -> dict:
    java = (repo / JAVA).read_text(encoding="utf-8")
    ts = (repo / FRONT).read_text(encoding="utf-8")
    enum_block = re.search(r"enum\s+AuditEventType\s*\{([^}]*)\}", java, re.S)
    list_block = re.search(r"AUDIT_EVENT_TYPES\s*=\s*\[([^\]]*)\]\s*as const", ts, re.S)
    labels_block = re.search(r"Record<AuditEventType, string>\s*=\s*\{([^}]*)\}", ts, re.S)
    if not enum_block or not list_block or not labels_block:
        raise ValueError("Структура справочников изменилась; требуется пересмотр проверки")
    # Не игнорируем нераспознанные элементы при изменении синтаксиса enum/list.
    enum_body = enum_block[1].strip().rstrip(";").strip().rstrip(",")
    enum_codes = [part.strip() for part in enum_body.split(",")]
    ts_body = list_block[1].strip().rstrip(",")
    raw = [part.strip() for part in ts_body.split(",")]
    if not all(re.fullmatch(CODE, c) for c in enum_codes):
        raise ValueError("Нераспознанный элемент Java enum")
    if not all(re.fullmatch(r"'" + CODE + "'", c) for c in raw):
        raise ValueError("Нераспознанный элемент frontend")
    java_set = unique(enum_codes, "Java")
    frontend_set = unique([c[1:-1] for c in raw], "Frontend")
    labels = re.findall(r"(" + CODE + r")\s*:\s*'([^']+)'\s*,", labels_block[1])
    label_set = unique([k for k, _ in labels], "Локализация")
    for name, values in (("Frontend", frontend_set), ("Локализация", label_set)):
        if java_set != values:
            raise ValueError(f"{name}: отсутствуют {sorted(java_set-values)}, лишние {sorted(values-java_set)}")
    if any(not re.search(r"[А-Яа-яЁё]", v) for _, v in labels):
        raise ValueError("Найдена подпись без русской локализации")
    migrations = sorted((repo / MIGRATIONS).glob("V*__*.sql"))
    if not migrations:
        raise ValueError("Нет SQL-миграций")
    sql_codes = set()
    for migration in migrations:
        content = migration.read_text(encoding="utf-8")
        # Требуем SQL-упоминание точного литерала. Фактическую регистрацию
        # отдельный интеграционный тест проверяет в живой БД.
        sql_codes.update(re.findall(r"'(" + CODE + r")'", content))
    if java_set - sql_codes:
        raise ValueError(f"Нет SQL-литералов: {sorted(java_set-sql_codes)}")
    return {"types": len(java_set), "localized": len(label_set), "sql_mentions": len(java_set),
            "codes": sorted(java_set)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--inventory", action="store_true",
                        help="Текстовые ссылки на типы в коде и тестах, не доказательство записи")
    args = parser.parse_args()
    try:
        repo = args.repo.resolve()
        result = validate(repo)
        if args.inventory:
            result["references"] = inventory(repo, result["codes"])
        print(json.dumps(result, ensure_ascii=False, indent=2 if args.inventory else None))
        return 0
    except (OSError, ValueError) as exc:
        print(f"AUDIT CONTRACT BLOCKED: {exc}", file=sys.stderr)
        return 1


def inventory(repo: Path, codes: list[str]) -> dict:
    """Индекс точных текстовых ссылок для последующего ручного анализа."""
    refs = {code: {"runtime": [], "tests": [], "sql": []} for code in codes}
    matcher = re.compile(r"\b(?:" + "|".join(map(re.escape, codes)) + r")\b")
    for group, directory, pattern in (
        ("runtime", "backend/src/main/java", "*.java"),
        ("tests", "backend/src/test/java", "*.java"),
        ("sql", str(MIGRATIONS), "V*__*.sql"),
    ):
        root = repo / directory
        if not root.is_dir():
            raise ValueError(f"Нет обязательного каталога инвентаризации: {directory}")
        for path in sorted(root.rglob(pattern)):
            if path == repo / JAVA:
                continue
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                for code in sorted(set(matcher.findall(line))):
                    refs[code][group].append(f"{path.relative_to(repo)}:{number}")
    return refs


if __name__ == "__main__":
    raise SystemExit(main())
