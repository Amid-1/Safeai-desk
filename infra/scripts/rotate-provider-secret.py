#!/usr/bin/env python3
"""Atomically replace one provider secret on the Linux deployment host."""
import argparse
import fcntl
import getpass
import os
from pathlib import Path
import re
import secrets
import stat
import sys

NAMES = {"openai": "openai_api_key", "anthropic": "anthropic_api_key",
         "ocr": "knowledge_ocr_api_key"}
GID = 21001
MAX_BYTES = 4096


def validate_key(raw):
    key = raw.removesuffix(b"\n")
    if (not 16 <= len(key) <= MAX_BYTES
            or not re.fullmatch(rb"[A-Za-z0-9._=+/~\-]+", key)
            or key.lower().startswith((b"replace", b"change-me", b"changeme",
                                       b"your-", b"test-", b"dummy-"))):
        raise ValueError("Invalid provider credential format")
    return key


def secure_target(info, uid, gid):
    return (stat.S_ISREG(info.st_mode) and info.st_uid == uid
            and info.st_gid == gid and stat.S_IMODE(info.st_mode) == 0o640
            and info.st_nlink == 1)


def open_directory(directory, uid, gid):
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    info = os.fstat(fd)
    if info.st_uid != uid or info.st_gid != gid or stat.S_IMODE(info.st_mode) != 0o750:
        os.close(fd)
        raise ValueError("Unsafe secrets directory")
    return fd


def check_file(directory, name, *, uid=0, gid=GID):
    if name not in NAMES.values():
        raise ValueError("Unknown provider")
    fd = open_directory(directory, uid, gid)
    try:
        with os.fdopen(os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                               dir_fd=fd), "rb") as stream:
            if not secure_target(os.fstat(stream.fileno()), uid, gid):
                raise ValueError("Unsafe existing secret")
            validate_key(stream.read(MAX_BYTES + 2))
    finally:
        os.close(fd)


def rotate(directory, name, key, *, uid=0, gid=GID):
    if name not in NAMES.values():
        raise ValueError("Unknown provider")
    key = validate_key(key)
    # Directory and its parent must be controlled by the deployment operator.
    fd = open_directory(directory, uid, gid)
    tmp = None
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if not secure_target(os.stat(name, dir_fd=fd, follow_symlinks=False), uid, gid):
            raise ValueError("Unsafe existing secret; expected root:21001/0640 regular file")
        candidate = ".provider-rotation-" + secrets.token_hex(16)
        out = os.open(candidate, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=fd)
        tmp = candidate
        with os.fdopen(out, "wb") as stream:
            stream.write(key)
            stream.flush()
            os.fchown(stream.fileno(), uid, gid)
            os.fchmod(stream.fileno(), 0o640)
            os.fsync(stream.fileno())
        os.replace(tmp, name, src_dir_fd=fd, dst_dir_fd=fd)
        tmp = None
        os.fsync(fd)
    finally:
        if tmp is not None:
            os.unlink(tmp, dir_fd=fd)
        os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("provider", choices=NAMES)
    parser.add_argument("--secrets-dir", type=Path, default=Path("/etc/safeai/secrets"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--stdin", action="store_true", help="read from a protected input stream")
    mode.add_argument("--check", action="store_true", help="validate existing file without printing contents")
    args = parser.parse_args()
    try:
        if args.check:
            check_file(args.secrets_dir, NAMES[args.provider])
            print("PASS: provider credential file format and permissions")
            return 0
        if os.geteuid() != 0:
            raise ValueError("Run as root on the deployment host")
        if args.stdin:
            key = sys.stdin.buffer.read(MAX_BYTES + 2)
        else:
            if not sys.stdin.isatty() or not sys.stderr.isatty():
                raise ValueError("Interactive terminal required; or use --stdin")
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                key = getpass.getpass("New provider credential: ").encode("ascii")
        rotate(args.secrets_dir, NAMES[args.provider], key)
    except (OSError, ValueError, EOFError, KeyboardInterrupt, getpass.GetPassWarning):
        # Never print a raw exception; it could include an input value.
        print("BLOCKED: rotation failed; verify permissions, input and exclusive lock.", file=sys.stderr)
        return 1
    print("PASS: secret replaced. Recreate all backend containers; verify before revoking old key.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
