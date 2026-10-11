#!/usr/bin/env bash
set -Eeuo pipefail

image="${1:-safeai-postgres:contract-test}"
for cmd in docker openssl mktemp; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "Missing required command: $cmd" >&2
        exit 1
    }
done

docker image inspect "$image" >/dev/null 2>&1 || {
    echo "Test image not found: $image" >&2
    exit 1
}

prefix="safeai-pg-it-$$"
network="$prefix"
volume="$prefix-data"
postgres_container="$prefix-postgres"
workdir="$(mktemp -d "$HOME/.safeai-pg-it.XXXXXXXX")"
network_created=false
volume_created=false
postgres_created=false

cleanup() {
    if [[ "$postgres_created" == true ]]; then
        docker rm -f "$postgres_container" >/dev/null 2>&1 || true
    fi
    if [[ "$network_created" == true ]]; then
        docker network rm "$network" >/dev/null 2>&1 || true
    fi
    if [[ "$volume_created" == true ]]; then
        docker volume rm "$volume" >/dev/null 2>&1 || true
    fi
    rm -rf -- "$workdir"
}
trap cleanup EXIT

# Disposable credentials only; no real production secrets are read.
for secret in postgres_bootstrap_password db_migrator_password db_app_password; do
    openssl rand -hex 32 > "$workdir/$secret"
done
# PostgreSQL runs as a non-root user and must read test files.
# Test-only credentials are destroyed on exit.
chmod 0755 "$workdir"
chmod 0644 "$workdir"/*

docker network create "$network" >/dev/null
network_created=true
docker volume create "$volume" >/dev/null
volume_created=true

docker run -d \
    --name "$postgres_container" \
    --network "$network" \
    --network-alias postgres \
    --mount "type=volume,src=$volume,dst=/var/lib/postgresql/data" \
    --mount "type=bind,src=$workdir,dst=/run/secrets,readonly" \
    --env POSTGRES_DB=safeai_contract \
    --env POSTGRES_USER=safeai_bootstrap_it \
    --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres_bootstrap_password \
    "$image" >/dev/null
postgres_created=true

ready=false
for attempt in $(seq 1 60); do
    if docker exec "$postgres_container" \
        pg_isready -h 127.0.0.1 -U safeai_bootstrap_it \
        -d safeai_contract >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    docker logs --tail 50 "$postgres_container" >&2 || true
    echo "FAIL: PostgreSQL did not become ready" >&2
    exit 1
fi
echo "PASS: fresh PostgreSQL initialization"

run_bootstrap() {
    local iteration="$1"
    if ! docker run --rm \
        --network "$network" \
        --mount "type=bind,src=$workdir,dst=/run/secrets,readonly" \
        --env POSTGRES_HOST=postgres \
        --env POSTGRES_PORT=5432 \
        --env POSTGRES_DB=safeai_contract \
        --env POSTGRES_USER=safeai_bootstrap_it \
        --env SAFEAI_DB_MIGRATOR_USER=safeai_migrator_it \
        --env SAFEAI_DB_APP_USER=safeai_app_it \
        --entrypoint /bin/bash \
        "$image" /opt/safeai/10-create-app-roles.sh \
        > "$workdir/bootstrap-$iteration.log" 2>&1; then
        tail -n 50 "$workdir/bootstrap-$iteration.log" >&2
        echo "FAIL: db-bootstrap iteration $iteration" >&2
        exit 1
    fi
    echo "PASS: db-bootstrap iteration $iteration"
}

run_bootstrap 1
run_bootstrap 2

assert_sql() {
    local title="$1"
    local query="$2"
    local result
    result="$(docker exec "$postgres_container" \
        psql -U safeai_bootstrap_it -d safeai_contract -At \
        -c "$query")"
    if [[ "$result" != t ]]; then
        echo "FAIL: $title (got: $result)" >&2
        exit 1
    fi
    echo "PASS: $title"
}

assert_sql "vector extension installed" \
    "SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'vector')"
assert_sql "migrator and app roles exist" \
    "SELECT count(*) = 2 FROM pg_roles WHERE rolname IN ('safeai_migrator_it','safeai_app_it')"
assert_sql "runtime user has no elevated role attributes" \
    "SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls) FROM pg_roles WHERE rolname = 'safeai_app_it'"
assert_sql "runtime user cannot CREATE on public schema" \
    "SELECT has_schema_privilege('safeai_app_it','public','USAGE') AND NOT has_schema_privilege('safeai_app_it','public','CREATE')"
assert_sql "database owned by migrator" \
    "SELECT pg_get_userbyid(datdba) = 'safeai_migrator_it' FROM pg_database WHERE datname = 'safeai_contract'"

app_user="$(docker run --rm \
    --network "$network" \
    --mount "type=bind,src=$workdir,dst=/run/secrets,readonly" \
    --entrypoint /bin/sh \
    "$image" -ec '
        export PGPASSWORD="$(cat /run/secrets/db_app_password)"
        psql -h postgres -U safeai_app_it -d safeai_contract -At \
            -c "SELECT current_user"
    ')"
[[ "$app_user" == safeai_app_it ]] || {
    echo "FAIL: runtime user cannot authenticate over TCP" >&2
    exit 1
}
echo "PASS: runtime user authenticates over TCP"
echo "PASS: isolated PostgreSQL bootstrap integration test complete"
