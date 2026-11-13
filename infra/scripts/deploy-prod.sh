#!/usr/bin/env bash
set -Eeuo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
repo_dir="$(cd -- "$infra_dir/.." && pwd)"
env_file="${1:-$repo_dir/backend/.env.prod}"
[[ -f "$env_file" ]] || {
    echo "Production env file does not exist: $env_file" >&2
    exit 1
}
env_file="$(cd -- "$(dirname -- "$env_file")" && pwd)/$(basename -- "$env_file")"
export SAFEAI_RUNTIME_ENV_FILE="$env_file"
compose_file="$infra_dir/docker-compose.yml"

"$script_dir/validate-prod.sh" "$env_file"
docker compose --env-file "$env_file" -f "$compose_file" pull

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a
export SAFEAI_RUNTIME_ENV_FILE="$env_file"
postgres_secret_gid="$(
    docker image inspect \
        --format '{{ index .Config.Labels "io.safeai.secret-gid" }}' \
        "$POSTGRES_IMAGE"
)"
[[ "$postgres_secret_gid" == "$SAFEAI_SECRET_GID" ]] || {
    echo "PostgreSQL image secret GID mismatch: image=$postgres_secret_gid env=$SAFEAI_SECRET_GID" >&2
    exit 1
}

# Do not switch an existing physical PGDATA to a different runtime image.
# A logical restore on an isolated volume is NOT proof of in-place PGDATA safety.
current_pg="$(docker compose --env-file "$env_file" -f "$compose_file" ps -aq postgres)"
if [[ -n "$current_pg" ]]; then
    current_image="$(docker inspect --format '{{.Config.Image}}' "$current_pg")"
    [[ "$current_image" == "$POSTGRES_IMAGE" ]] || {
        echo 'BLOCKED: PostgreSQL image changed over an existing container.' >&2
        echo 'First complete a reviewed logical cutover to a NEW volume.' >&2
        exit 1
    }
elif docker volume inspect safeai_postgres_data >/dev/null 2>&1; then
    echo 'BLOCKED: unknown existing PostgreSQL volume without container.' >&2
    echo 'Require a separate reviewed migration/cutover procedure.' >&2
    exit 1
fi

docker compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    up -d --wait --wait-timeout 180 postgres redis

# Recreate the one-shot bootstrap job on every deployment.
# Do not recreate PostgreSQL or Redis as a side effect.
docker compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    up -d --no-deps --force-recreate db-bootstrap

bootstrap_id="$(
    docker compose \
        --env-file "$env_file" \
        -f "$compose_file" \
        ps -aq db-bootstrap
)"

[[ -n "$bootstrap_id" ]] || {
    echo "db-bootstrap container was not created" >&2
    exit 1
}

bootstrap_exit="$(docker wait "$bootstrap_id")"

[[ "$bootstrap_exit" == "0" ]] || {
    echo "db-bootstrap failed (exit code: $bootstrap_exit)" >&2
    exit 1
}

echo "Database role bootstrap completed successfully."

docker compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    up -d \
    --wait \
    --wait-timeout 180 \
    --remove-orphans \
    backend nginx prometheus

docker compose --env-file "$env_file" -f "$compose_file" ps
curl --fail --silent --show-error "http://${SAFEAI_PUBLIC_BIND}/healthz"
echo
docker compose --env-file "$env_file" -f "$compose_file" \
    exec -T backend curl --fail --silent --show-error \
    http://172.31.0.20:9091/actuator/health/readiness
