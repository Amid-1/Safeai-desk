#!/usr/bin/env python3
# SafeAI-Desk/infra/scripts/update-prod-images.py
"""Атомарно обновляет digest трёх образов SafeAI после успешной публикации релиза."""

from __future__ import annotations

import os
import re
import stat
import sys
import tempfile
from pathlib import Path


IMAGE_RULES = {
    "SAFEAI_BACKEND_IMAGE": re.compile(
        r"ghcr\.io/[a-z0-9]+(?:[.-][a-z0-9]+)*/safeai-backend@sha256:[0-9a-f]{64}"
    ),
    "SAFEAI_NGINX_IMAGE": re.compile(
        r"ghcr\.io/[a-z0-9]+(?:[.-][a-z0-9]+)*/safeai-nginx@sha256:[0-9a-f]{64}"
    ),
    "POSTGRES_IMAGE": re.compile(
        r"ghcr\.io/[a-z0-9]+(?:[.-][a-z0-9]+)*/safeai-postgres@sha256:[0-9a-f]{64}"
    ),
}


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        raise SystemExit(
            "Usage: update-prod-images.py backend/.env.prod "
            "<backend@sha256> <nginx@sha256> <postgres@sha256>"
        )

    env_path = Path(argv[1])
    if env_path.is_symlink() or not env_path.is_file():
        raise SystemExit(f"Refusing missing, non-regular or symlink env file: {env_path}")

    supplied = dict(zip(IMAGE_RULES, argv[2:], strict=True))
    for key, image in supplied.items():
        if not IMAGE_RULES[key].fullmatch(image):
            raise SystemExit(f"Invalid immutable image reference for {key}")

    original = env_path.read_text(encoding="utf-8")
    lines = original.splitlines(keepends=True)
    for key, image in supplied.items():
        indexes = [i for i, line in enumerate(lines) if line.startswith(key + "=")]
        if len(indexes) != 1:
            raise SystemExit(f"Expected exactly one {key} in {env_path}")
        index = indexes[0]
        ending = "\r\n" if lines[index].endswith("\r\n") else (
            "\n" if lines[index].endswith("\n") else ""
        )
        lines[index] = f"{key}={image}{ending}"

    updated = "".join(lines)
    metadata = env_path.stat()
    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{env_path.name}.", suffix=".tmp", dir=env_path.parent
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as target:
            target.write(updated)
            target.flush()
            os.fsync(target.fileno())
        os.chmod(temporary_name, stat.S_IMODE(metadata.st_mode))
        try:
            os.chown(temporary_name, metadata.st_uid, metadata.st_gid)
        except PermissionError:
            temporary_stat = os.stat(temporary_name)
            if (temporary_stat.st_uid, temporary_stat.st_gid) != (
                metadata.st_uid, metadata.st_gid
            ):
                raise
        os.replace(temporary_name, env_path)
        directory_fd = os.open(env_path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except Exception:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise

    print("Updated SAFEAI_BACKEND_IMAGE, SAFEAI_NGINX_IMAGE and POSTGRES_IMAGE.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
