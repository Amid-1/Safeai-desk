#!/usr/bin/env bash
# SafeAI-Desk: apply the reviewed V1-V99 runtime ACL to the local Docker DB.
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
        printf '%s\n' "IF NOT (SELECT count(*)=99 AND count(DISTINCT version)=99 AND bool_and(success AND version ~ '^[1-9][0-9]*$' AND version::int BETWEEN 1 AND 99) FROM public.flyway_schema_history WHERE version IS NOT NULL) THEN"
        printf '%s\n' "RAISE EXCEPTION 'Expected reviewed schema V1-V99; run migrations first'; END IF;"
        printf '%s\n' "END; \$guard\$;"
        cat "$sql_file"
    } | docker compose -f docker-compose.local.yml exec -T -e PGPASSWORD postgres \
        psql -X -v ON_ERROR_STOP=1 -1 -h 127.0.0.1 -U safeai_migrator -d safeai -f -
)

echo "PASS: reviewed V1-V99 runtime ACL applied to local safeai database"
