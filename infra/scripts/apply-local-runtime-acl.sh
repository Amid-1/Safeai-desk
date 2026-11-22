#!/usr/bin/env bash
# SafeAI-Desk: apply the reviewed V1-V101 runtime ACL to the local Docker DB.
# Stop the local backend before invocation. Never use this for production.
set -Eeuo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
acl_dir="$repo_dir/backend/src/main/resources/db/acl"
sql_file="$acl_dir/runtime-v90.sql"

[[ -f "$sql_file" && -f "$acl_dir/reviewed-migrations.sha256" ]] || {
    echo "Missing reviewed runtime ACL resources" >&2
    exit 1
}
(
    cd "$repo_dir/backend/src/main/resources/db/migration"
    # Match the explicit Java review boundary before even connecting to PostgreSQL.
    # Reject a stale, incomplete or expanded manifest and unreviewed SQL/CONF files.
    python3 - "$acl_dir/reviewed-migrations.sha256" <<'PY'
import re
import sys
from pathlib import Path

def fail(message):
    raise SystemExit(f"Runtime ACL review required: {message}")

entries = set()
versions = set()
for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if not line.strip() or line.startswith("#"):
        continue
    match = re.fullmatch(r"[a-f0-9]{64}  (V([1-9][0-9]*)__[^/\\\\]+\.sql(?:\.conf)?)", line)
    if not match or match[1] in entries:
        fail("invalid or duplicate manifest entry")
    name, version = match[1], int(match[2])
    entries.add(name)
    if name.endswith(".sql"):
        if version in versions:
            fail("duplicate reviewed version")
        versions.add(version)
if versions != set(range(1, 102)):
    fail("review boundary mismatch (expected V1-V101)")
if any(name.endswith(".conf") and name[:-5] not in entries for name in entries):
    fail("orphan migration configuration")
actual = {p.name for p in Path(".").iterdir()
          if p.name.endswith((".sql", ".sql.conf"))}
if entries != actual or any(Path(name).is_symlink() or not Path(name).is_file()
                            for name in entries):
    fail("migration inventory mismatch")
PY
    awk 'NF && !/^#/' "$acl_dir/reviewed-migrations.sha256" | shasum -a 256 -c --strict -
)

set -a
source "$repo_dir/backend/.env"
set +a

[[ "${SPRING_DATASOURCE_USERNAME:-}" == safeai_app && "${SPRING_FLYWAY_USER:-safeai_migrator}" == safeai_migrator ]] || {
    echo "Unexpected local database role configuration" >&2
    exit 1
}
: "${SPRING_FLYWAY_PASSWORD:?Missing local migrator password}"

cd "$repo_dir/infra"
(
    export PGPASSWORD="$SPRING_FLYWAY_PASSWORD"
    {
        printf '%s\n' "SET LOCAL lock_timeout = '5s'; SET LOCAL statement_timeout = '120s';"
        printf '%s\n' "DO \$guard\$ BEGIN"
        printf '%s\n' "IF current_database() <> 'safeai' OR session_user <> 'safeai_migrator' OR current_user <> session_user THEN"
        printf '%s\n' "RAISE EXCEPTION 'Unexpected local database or migration identity'; END IF;"
        printf '%s\n' "IF NOT (SELECT count(*)=101 AND count(DISTINCT version)=101 AND bool_and(success AND version ~ '^[1-9][0-9]*$' AND version::int BETWEEN 1 AND 101) FROM public.flyway_schema_history WHERE version IS NOT NULL) THEN"
        printf '%s\n' "RAISE EXCEPTION 'Expected reviewed schema V1-V101; run migrations first'; END IF;"
        printf '%s\n' "END; \$guard\$;"
        cat "$sql_file"
    } | docker compose -f docker-compose.local.yml exec -T -e PGPASSWORD postgres \
        psql -X -v ON_ERROR_STOP=1 -1 -h 127.0.0.1 -U safeai_migrator -d safeai -f -
)

echo "PASS: reviewed V1-V101 runtime ACL applied to local safeai database"
