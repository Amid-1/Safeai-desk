#!/usr/bin/env python3
"""Explicit, fail-closed RateLimit epoch administration; no secret in argv or output."""

import argparse
import hashlib
import hmac
import re
import subprocess
import sys
from pathlib import Path

KEY = "safeai:rate-limit:guard:{login}:epoch"
ROOT = Path(__file__).resolve().parents[2]
ACTIONS = ("status", "initialize-empty", "begin-missing", "pause", "activate", "rebind-preserved")
CONFIRMATIONS = {
    "initialize-empty": "FIRST_INSTALLATION_NO_PRIOR_TRAFFIC",
    "begin-missing": "ALL_LEGACY_BACKENDS_STOPPED",
    "pause": "ALL_LEGACY_BACKENDS_STOPPED",
    "activate": "DRAIN_AND_PRIMARY_VERIFIED",
    "rebind-preserved": "OLD_PRIMARY_FENCED_AND_COUNTERS_PROVEN",
}


def fingerprint(prefix: str, version: str, secret_file: Path) -> str:
    if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", prefix) or prefix.endswith(":"):
        raise ValueError("Use the exact normalized Redis key prefix (no trailing colon)")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,32}", version):
        raise ValueError("Invalid key version")
    secret = secret_file.read_bytes()
    secret.decode("utf-8", errors="strict")
    if len(secret) < 32 or any(c in secret for c in (b"\r", b"\n", b"\x00")):
        raise ValueError("Secret file must contain at least 32 UTF-8 bytes, without CR/LF/NUL")
    message = f"rate-limit-epoch-v1:{prefix}\n{version}".encode("utf-8")
    return hmac.new(secret, message, hashlib.sha256).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=ACTIONS)
    parser.add_argument("--prefix")
    parser.add_argument("--version")
    parser.add_argument("--secret-file", type=Path)
    parser.add_argument("--new-prefix")
    parser.add_argument("--new-version")
    parser.add_argument("--new-secret-file", type=Path)
    parser.add_argument("--confirm")
    parser.add_argument("--redis-cli", nargs=argparse.REMAINDER, required=True,
                        help="Command prefix, ending in redis-cli; use REDISCLI_AUTH, not -a")
    args = parser.parse_args()
    try:
        if not args.redis_cli:
            raise ValueError("Missing redis-cli command")
        if args.action == "status":
            old = new = ""
        else:
            if args.confirm != CONFIRMATIONS[args.action]:
                raise ValueError(f"Required --confirm {CONFIRMATIONS[args.action]}")
            if not all((args.prefix, args.version, args.secret_file)):
                raise ValueError("The current prefix, version and secret file are required")
            old = fingerprint(args.prefix, args.version, args.secret_file)
            new = old
            if args.action == "activate":
                if not all((args.new_prefix, args.new_version, args.new_secret_file)):
                    raise ValueError("Activation requires the explicit new namespace and secret file")
                new = fingerprint(args.new_prefix, args.new_version, args.new_secret_file)
            elif any((args.new_prefix, args.new_version, args.new_secret_file)):
                raise ValueError("New namespace options are allowed only for activation")
        script = (ROOT / "backend/src/main/resources/ratelimit/epoch-admin.lua").read_text(encoding="utf-8")
        result = subprocess.run(args.redis_cli + ["-e", "--raw", "EVAL", script, "1", KEY,
                                                 args.action, old, new],
                                capture_output=True, text=True, timeout=30, check=False)
        value = result.stdout.strip()
        if result.returncode != 0 or not re.fullmatch(r"ACTIVE|MISSING|PRIMARY_CHANGED|PAUSED(?::\d+)?", value):
            print("RateLimit epoch operation rejected or transport uncertain; inspect status before retry.",
                  file=sys.stderr)
            return 1
        print(value)
        return 0
    except (ValueError, OSError, subprocess.TimeoutExpired) as exc:
        # File contents and redis-cli arguments never appear in the diagnostic.
        print(f"RateLimit epoch operation failed ({type(exc).__name__}).", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
