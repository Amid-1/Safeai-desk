#!/usr/bin/env bash
set -Eeuo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
repo_dir="$(cd -- "$infra_dir/.." && pwd)"
env_file="${1:-$repo_dir/backend/.env.prod}"
compose_file="$infra_dir/docker-compose.yml"

"$script_dir/validate-prod.sh" "$env_file"
docker compose --env-file "$env_file" -f "$compose_file" pull

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a
postgres_secret_gid="$(
    docker image inspect \
        --format '{{ index .Config.Labels "io.safeai.secret-gid" }}' \
        "$POSTGRES_IMAGE"
)"
[[ "$postgres_secret_gid" == "$SAFEAI_SECRET_GID" ]] || {
    echo "PostgreSQL image secret GID mismatch: image=$postgres_secret_gid env=$SAFEAI_SECRET_GID" >&2
    exit 1
}

docker compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    up -d postgres redis

docker compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    --profile ops \
    run --rm db-bootstrap

docker compose \
    --env-file "$env_file" \
    -f "$compose_file" \
    up -d \
    --wait \
    --wait-timeout 180 \
    --remove-orphans \
    backend nginx

docker compose --env-file "$env_file" -f "$compose_file" ps
curl --fail --silent --show-error http://127.0.0.1:8080/healthz
echo
curl --fail --silent --show-error http://127.0.0.1:8080/readyz
echo
