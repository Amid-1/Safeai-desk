#!/usr/bin/env python3
"""Проверка целостности и append-only истории Flyway."""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

MIGRATIONS = Path("backend/src/main/resources/db/migration")
PATTERN = re.compile(r"^V([1-9][0-9]*)__([A-Za-z0-9][A-Za-z0-9_-]*)\.sql$")


def run_git(repo: Path, *args: str) -> bytes:
    result = subprocess.run(
        ["git", *args], cwd=repo, capture_output=True, check=False
    )
    if result.returncode:
        detail = result.stderr.decode(errors="replace").strip()[:500]
        raise ValueError(f"git {' '.join(args[:2])} завершился ошибкой: {detail}")
    return result.stdout


def validate_files(repo: Path) -> list[tuple[int, Path]]:
    directory = repo / MIGRATIONS
    if not directory.is_dir():
        raise ValueError(f"Нет каталога {MIGRATIONS}")

    seen: dict[int, Path] = {}
    for path in sorted(directory.iterdir()):
        if not path.is_file() or path.name.startswith("."):
            continue
        if path.suffix.lower() != ".sql":
            continue
        match = PATTERN.fullmatch(path.name)
        if not match:
            raise ValueError(f"Некорректное имя миграции: {path.name}")
        version = int(match.group(1))
        if version in seen:
            raise ValueError(
                f"Повтор версии V{version}: {seen[version].name} и {path.name}"
            )
        if not path.read_bytes().strip():
            raise ValueError(f"Пустая миграция V{version}: {path.name}")
        seen[version] = path

    if not seen:
        raise ValueError("Не найдено ни одной SQL-миграции")

    missing = sorted(set(range(1, max(seen) + 1)) - set(seen))
    if missing:
        raise ValueError(f"Пропущены версии Flyway: {missing}")
    return sorted(seen.items())


def validate_immutability(repo: Path, base_ref: str) -> tuple[int, int]:
    files = validate_files(repo)
    base_output = run_git(
        repo, "ls-tree", "-r", "--name-only", base_ref, "--", str(MIGRATIONS)
    )
    published = [
        name for name in base_output.decode().splitlines()
        if name.endswith(".sql")
    ]
    current = {str(path.relative_to(repo)): path for _, path in files}

    for old_path in published:
        if old_path not in current:
            raise ValueError(f"Удалена опубликованная миграция: {old_path}")
        previous = run_git(repo, "show", f"{base_ref}:{old_path}")
        if previous != current[old_path].read_bytes():
            raise ValueError(
                f"Изменена историческая миграция: {old_path}. "
                "Добавь новую версию вместо редактирования старой."
            )

    return len(files), len(published)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--base", help="Git ref опубликованной истории, например origin/main")
    args = parser.parse_args()
    try:
        repo = args.repo.resolve()
        if args.base:
            count, published = validate_immutability(repo, args.base)
            print(
                f"FLYWAY OK: миграций={count}; опубликованных неизменённых={published}; "
                f"ref={args.base}"
            )
        else:
            count = len(validate_files(repo))
            print(f"FLYWAY OK: миграций={count}; последовательность целая")
        return 0
    except (OSError, ValueError) as exc:
        print(f"FLYWAY FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
