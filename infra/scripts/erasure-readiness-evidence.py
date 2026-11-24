#!/usr/bin/env python3
# infra/scripts/erasure-readiness-evidence.py
"""Fail-closed evidence inventory. This does NOT inspect live S3, authorize erasure or enable workers."""

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

REQUIRED = (
    "addressed_request_workflow_v103",
    "independent_high_impact_approval",
    "migration_and_runtime_acl",
    "chat_worker_database_role",
    "knowledge_worker_database_role",
    "s3_versioning_and_object_lock",
    "independent_immutable_erasure_journal",
    "verified_all_object_versions_purge",
    "tenant_and_owner_isolation",
    "retention_holds_and_legal_protection",
    "crash_recovery_and_lease_fencing",
    "user_and_organization_protection",
    "audit_evidence_and_privacy",
    "monitoring_and_restore_drill",
    "full_ci_same_release_sha",
)
SHA = re.compile(r"[a-f0-9]{64}\Z")
RELATIVE = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9._/-]*\Z")


def template():
    return {
        "environment": "staging",
        "release_sha": "REPLACE_WITH_FULL_GIT_SHA",
        "checks": [
            {"id": name, "status": "NOT_RUN", "observed_at": None,
             "artifact": None, "sha256": None, "reviewers": []}
            for name in REQUIRED
        ],
    }


def check(data: dict, base: Path, now: datetime | None = None) -> list[str]:
    now = now or datetime.now(timezone.utc)
    problems: list[str] = []
    if not isinstance(data, dict):
        return ["Root must be a JSON object"]
    if data.get("environment") not in ("staging", "production"):
        problems.append("environment must be staging or production")
    release_sha = data.get("release_sha")
    if not isinstance(release_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", release_sha):
        problems.append("release_sha must be the exact 40-hex release commit")
    checks = data.get("checks")
    if not isinstance(checks, list):
        return problems + ["checks must be a list"]
    seen: set[str] = set()
    for entry in checks:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            problems.append("each check requires an id")
            continue
        name = entry["id"]
        if name in seen:
            problems.append(f"duplicate check: {name}")
        seen.add(name)
        if name not in REQUIRED:
            problems.append(f"unknown check: {name}")
            continue
        if entry.get("status") != "PASS":
            problems.append(f"{name}: not PASS")
            continue
        timestamp = entry.get("observed_at")
        try:
            if not isinstance(timestamp, str) or not timestamp.endswith("Z"):
                raise ValueError("must be UTC Z")
            observed = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            if observed.tzinfo is None or not now - timedelta(days=7) <= observed <= now:
                raise ValueError("stale or future observation")
        except ValueError:
            problems.append(f"{name}: observation missing, stale or not UTC")
        path_value = entry.get("artifact")
        if (not isinstance(path_value, str) or not RELATIVE.fullmatch(path_value)
                or path_value.startswith("/") or any(p in (".", "..") for p in Path(path_value).parts)):
            problems.append(f"{name}: invalid evidence artifact path")
            continue
        artifact = (base / path_value).resolve()
        try:
            artifact.relative_to(base.resolve())
        except ValueError:
            problems.append(f"{name}: evidence escapes directory")
            continue
        if not artifact.is_file() or artifact.is_symlink():
            problems.append(f"{name}: evidence artifact missing or symbolic link")
            continue
        if artifact.stat().st_size == 0 or artifact.stat().st_size > 20_000_000:
            problems.append(f"{name}: evidence artifact empty or too large")
            continue
        expected = entry.get("sha256")
        actual = hashlib.sha256(artifact.read_bytes()).hexdigest()
        if not isinstance(expected, str) or not SHA.fullmatch(expected) or actual != expected:
            problems.append(f"{name}: evidence checksum mismatch")
        reviewers = entry.get("reviewers")
        if (not isinstance(reviewers, list) or not reviewers
                or len(reviewers) != len(set(str(r) for r in reviewers))
                or not all(isinstance(r, str) and re.fullmatch(r"[A-Za-z0-9_.-]{3,64}", r)
                           for r in reviewers)):
            problems.append(f"{name}: valid independent reviewer IDs required")
        if name == "independent_high_impact_approval" and isinstance(reviewers, list):
            if len(reviewers) < 2:
                problems.append(f"{name}: two distinct reviewers required")
    for missing in sorted(set(REQUIRED) - seen):
        problems.append(f"missing check: {missing}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--template", action="store_true", help="print a NOT_RUN template to stdout")
    group.add_argument("--check", type=Path, help="validate operator evidence JSON without side effects")
    args = parser.parse_args()
    if args.template:
        print(json.dumps(template(), ensure_ascii=False, indent=2))
        return 0
    try:
        file = args.check.resolve()
        data = json.loads(file.read_text(encoding="utf-8"))
        errors = check(data, file.parent)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        print(f"ERASURE NO-GO: evidence cannot be read: {exc}", file=sys.stderr)
        return 2
    if errors:
        for problem in errors:
            print(f"ERASURE NO-GO: {problem}", file=sys.stderr)
        return 2
    print("EVIDENCE INVENTORY PASS (documents only; NOT proof of actual runtime readiness or deploy GO)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
