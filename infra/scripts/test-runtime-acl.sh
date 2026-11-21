#!/usr/bin/env bash
# Запуск только на временных Docker-ресурсах, без env.prod и production secrets.
set -Eeuo pipefail
[[ $# == 2 ]] || { echo 'Usage: test-runtime-acl.sh <postgres-image> <backend-image>' >&2; exit 1; }
pg_image="$(docker image inspect --format '{{.Id}}' "$1")"
backend_image="$(docker image inspect --format '{{.Id}}' "$2")"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
prefix="safeai-rt-$(openssl rand -hex 8)"
workdir="$(mktemp -d)"
network="$prefix"
redis="$prefix-redis"
pg_clean="$prefix-clean"
pg_upgrade="$prefix-upgrade"
cleanup() {
    docker rm -f -v "$pg_clean" "$pg_upgrade" "$redis" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    rm -r -- "$workdir"
}
trap cleanup EXIT
for secret in postgres_bootstrap_password db_migrator_password db_app_password; do
    openssl rand -hex 32 > "$workdir/$secret"
done
chmod 0755 "$workdir"
chmod 0644 "$workdir"/*
docker network create "$network" >/dev/null
docker run -d --name "$redis" --network "$network" -p 127.0.0.1::6379 redis:8-alpine >/dev/null
redis_port="$(docker port "$redis" 6379/tcp | sed 's/.*://')"
for i in $(seq 1 60); do
    if docker exec "$redis" redis-cli ping | grep -qx PONG; then break; fi
    [[ "$i" != 60 ]] || exit 1
    sleep 1
done

bootstrap() {
    docker run --rm --network "$network" \
        --mount "type=bind,src=$workdir,dst=/run/secrets,readonly" \
        --env POSTGRES_HOST="$pg" --env POSTGRES_DB="$db" --env POSTGRES_USER=safeai_contract_dba \
        --env SAFEAI_DB_MIGRATOR_USER=safeai_migrator --env SAFEAI_DB_APP_USER=safeai_app \
        --entrypoint /bin/bash "$pg_image" /opt/safeai/10-create-app-roles.sh
}
migrate() {
    docker run --rm --network "$network" \
        --mount "type=bind,src=$workdir/db_migrator_password,dst=/run/secrets/db_migrator_password,readonly" \
        --env SAFEAI_MIGRATION_JDBC_URL="jdbc:postgresql://$pg:5432/$db" \
        --env SAFEAI_MIGRATION_PASSWORD_FILE=/run/secrets/db_migrator_password \
        --entrypoint java "$backend_image" \
        -Dloader.main=ru.safeai.gateway.common.db.DatabaseMigrationMain -cp /app/app.jar \
        org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}
sql() {
    docker exec -i "$pg" psql -X -v ON_ERROR_STOP=1 -U safeai_contract_dba -d "$db" "$@"
}
checkpoint_export() {
    docker run --rm -i --network "$network" --entrypoint psql \
        --env PGPASSWORD="$reader_password" "$pg_image" -X -q -w -v ON_ERROR_STOP=1 \
        -h "$pg" -U safeai_audit_verifier -d "$db" -f - \
        < "$repo_dir/infra/scripts/audit-checkpoint-export.sql"
}
test_checkpoint_reader() {
    local reader_password
    reader_password="$(openssl rand -hex 32)"
    sql -v reader_password="$reader_password" <<'SQL'
CREATE ROLE safeai_audit_verifier LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT;
SELECT format('ALTER ROLE safeai_audit_verifier PASSWORD %L', :'reader_password') \gexec
GRANT USAGE ON SCHEMA public TO safeai_audit_verifier;
GRANT SELECT ON public.audit_events TO safeai_audit_verifier;
SQL
    # Bootstrap revoked implicit CONNECT/TEMPORARY from PUBLIC. Prove that
    # the test reader has no implicit access before granting only CONNECT.
    [[ "$(sql -Atc "SELECT NOT has_database_privilege('safeai_audit_verifier',current_database(),'CONNECT')")" == t ]]
    sql <<'SQL'
SELECT format('GRANT CONNECT ON DATABASE %I TO safeai_audit_verifier', current_database()) \gexec
INSERT INTO public.audit_events(id,organization_id,event_type,details,created_at,correlation_id)
VALUES ('ac000000-0000-4000-8000-000000000010','ac000000-0000-4000-8000-000000000020',
        'USER_CREATED','{"checkpoint":"sentinel"}',clock_timestamp(),'ac000000-0000-4000-8000-000000000030');
SQL
    [[ "$(sql -Atc "SELECT
        has_database_privilege('safeai_audit_verifier',current_database(),'CONNECT')
        AND NOT has_database_privilege('safeai_audit_verifier',current_database(),'TEMPORARY')
        AND NOT has_database_privilege('safeai_audit_verifier',current_database(),'CREATE')
        AND NOT has_table_privilege('safeai_audit_verifier','public.users','SELECT')
        AND NOT has_table_privilege('safeai_audit_verifier','public.flyway_schema_history','SELECT')")" == t ]]
    checkpoint_export > "$workdir/checkpoint-before.csv"
    # Neither a DBA nor an overprivileged reader may produce a checkpoint.
    if sql -q -f - < "$repo_dir/infra/scripts/audit-checkpoint-export.sql" >/dev/null 2>&1; then
        echo 'FAIL: privileged DBA accepted as checkpoint reader' >&2; return 1
    fi
    sql -c 'GRANT DELETE ON public.audit_events TO safeai_audit_verifier'
    if checkpoint_export >/dev/null 2>&1; then
        echo 'FAIL: writable checkpoint reader accepted' >&2; return 1
    fi
    sql -c 'REVOKE DELETE ON public.audit_events FROM safeai_audit_verifier'
    sql -c "DELETE FROM public.audit_events WHERE id='ac000000-0000-4000-8000-000000000010'"
    checkpoint_export > "$workdir/checkpoint-after.csv"
    python3 - "$repo_dir/infra/scripts/audit-checkpoint.py" "$workdir" <<'PY'
import importlib.util
import io
import pathlib
import sys
spec = importlib.util.spec_from_file_location('checkpoint', sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
root = pathlib.Path(sys.argv[2])
dataset = 'ac000000-0000-4000-8000-000000000001'
def load(name, previous):
    out = io.BytesIO()
    with (root / name).open(encoding='utf-8', newline='') as stream:
        module.export_rows(stream, out, module.make_header(dataset, previous))
    return module.Manifest(out.getvalue())
before = load('checkpoint-before.csv', None)
after = load('checkpoint-after.csv', before.sha256)
assert 'ac000000-0000-4000-8000-000000000010' in dict(before.events())
assert module.compare(before, after) == {'matched': after.count, 'missing': 1, 'changed': 0, 'new_unanchored': 0}
assert b'sentinel' not in before.data
PY
    echo 'PASS: actual PostgreSQL checkpoint projection, ACL and deletion detection'
}
for scenario in clean upgrade; do
    pg="$prefix-$scenario"
    db="safeai_contract_$scenario"
    docker run -d --name "$pg" --network "$network" -p 127.0.0.1::5432 \
        --mount "type=bind,src=$workdir/postgres_bootstrap_password,dst=/run/secrets/postgres_bootstrap_password,readonly" \
        --env POSTGRES_DB="$db" --env POSTGRES_USER=safeai_contract_dba \
        --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres_bootstrap_password \
        "$pg_image" >/dev/null
    for i in $(seq 1 90); do
        if docker exec "$pg" pg_isready -h 127.0.0.1 -U safeai_contract_dba -d "$db" >/dev/null; then break; fi
        [[ "$i" != 90 ]] || { docker logs --tail 50 "$pg" >&2; exit 1; }
        sleep 1
    done
    bootstrap
    if [[ "$scenario" == upgrade ]]; then
        migrate ci-v85
        # Воспроизводим прежнюю широкую ACL и непустую базу V85, не меняя историю Flyway.
        sql <<'SQL'
BEGIN;
INSERT INTO public.organizations(id,name,normalized_name,enabled,created_at,updated_at,version)
VALUES ('6d00b9d4-b311-4be6-aee0-222222222222','Upgrade sentinel',public.normalize_organization_name('Upgrade sentinel'),true,clock_timestamp(),clock_timestamp(),0);
INSERT INTO public.users(id,organization_id,email,password_hash,full_name,enabled,disabled_at,created_at,updated_at,token_version,version)
VALUES ('6d00b9d4-b311-4be6-aee0-333333333333','6d00b9d4-b311-4be6-aee0-222222222222','upgrade-sentinel@example.invalid','disabled-test-only','Upgrade sentinel',true,NULL,clock_timestamp(),clock_timestamp(),0,0);
INSERT INTO public.user_roles(user_id,role_id)
SELECT '6d00b9d4-b311-4be6-aee0-333333333333', id
FROM public.roles WHERE name = 'USER';
INSERT INTO public.chat_sessions(id,user_id,organization_id,title,created_at,updated_at,version)
VALUES ('6d00b9d4-b311-4be6-aee0-444444444444','6d00b9d4-b311-4be6-aee0-333333333333','6d00b9d4-b311-4be6-aee0-222222222222','Upgrade sentinel chat',clock_timestamp(),clock_timestamp(),0);
COMMIT;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO safeai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE safeai_migrator IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO safeai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE safeai_migrator IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO safeai_app;
SQL
        bootstrap
    fi
    migrate
    # Повторный bootstrap/deploy не должен вернуть широкие разрешения.
    bootstrap
    migrate
    # Independent worker logins, not SET ROLE from a superuser. Passwords exist
    # only in these disposable containers; production destructive flags stay off.
    for worker in safeai_chat_erasure_worker safeai_knowledge_erasure_worker; do
        worker_password="$(openssl rand -hex 32)"
        sql -v worker="$worker" -v worker_password="$worker_password" <<'SQL'
SELECT format('ALTER ROLE %I PASSWORD %L', :'worker', :'worker_password') \gexec
SQL
        worker_identity="$(docker run --rm --network "$network" --entrypoint psql \
            --env PGPASSWORD="$worker_password" "$pg_image" -X -v ON_ERROR_STOP=1 \
            -h "$pg" -U "$worker" -d "$db" -Atc \
            "SELECT session_user=current_user AND NOT has_schema_privilege(current_user,'public','CREATE') AND NOT has_table_privilege(current_user,'public.flyway_schema_history','SELECT')")"
        [[ "$worker_identity" == t ]]
        unset worker_password
    done
    if [[ "$scenario" == upgrade ]]; then
        [[ "$(sql -Atc "SELECT count(*)=1 FROM public.organizations WHERE id='6d00b9d4-b311-4be6-aee0-222222222222' AND name='Upgrade sentinel'")" == t ]]
        [[ "$(sql -Atc "SELECT count(*)=1 FROM public.chat_sessions WHERE id='6d00b9d4-b311-4be6-aee0-444444444444' AND title='Upgrade sentinel chat'")" == t ]]
    fi
    pg_port="$(docker port "$pg" 5432/tcp | sed 's/.*://')"
    export SAFEAI_CONTRACT_JDBC_URL="jdbc:postgresql://127.0.0.1:$pg_port/$db"
    export SAFEAI_CONTRACT_DB_PASSWORD="$(tr -d '\r\n' < "$workdir/db_app_password")"
    export SAFEAI_CONTRACT_REDIS_PORT="$redis_port"
    export SAFEAI_CONTRACT_STORAGE="$workdir/storage-$scenario"
    (cd "$repo_dir/backend" && ./mvnw --batch-mode --no-transfer-progress -Dtest=RuntimeAclContract test)
    report="$repo_dir/backend/target/surefire-reports/TEST-ru.safeai.gateway.knowledge.integration.RuntimeAclContract.xml"
    python3 - "$report" <<'PY'
import sys
from xml.etree.ElementTree import parse
root = parse(sys.argv[1]).getroot()
assert int(root.attrib['tests']) == 1
assert all(int(root.attrib[x]) == 0 for x in ('failures', 'errors', 'skipped'))
PY
    mkdir -p "$repo_dir/backend/target/runtime-acl-reports/$scenario"
    cp "$report" "$repo_dir/backend/target/runtime-acl-reports/$scenario/"
    unset SAFEAI_CONTRACT_DB_PASSWORD SAFEAI_CONTRACT_JDBC_URL SAFEAI_CONTRACT_REDIS_PORT SAFEAI_CONTRACT_STORAGE
    test_checkpoint_reader
    echo "PASS: $scenario / postgres=$pg_image / backend=$backend_image"
done
