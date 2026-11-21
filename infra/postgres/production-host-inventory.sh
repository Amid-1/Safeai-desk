#!/usr/bin/env bash
# Только локальная инвентаризация Docker. Не читает секреты и файлы БД.
set -Eeuo pipefail

if [[ "${1:-}" != --read-only || "$#" -ne 1 ]]; then
    echo 'Usage: bash infra/postgres/production-host-inventory.sh --read-only' >&2
    exit 2
fi
command -v docker >/dev/null || { echo 'BLOCKED: Docker CLI не найден' >&2; exit 1; }
docker info >/dev/null || { echo 'BLOCKED: Docker daemon недоступен' >&2; exit 1; }

printf 'Docker context: %s\n' "$(docker context show)"
postgres_containers="$(
    docker ps -aq \
        --filter label=com.docker.compose.project=safeai \
        --filter label=com.docker.compose.service=postgres
)"
if [[ "$postgres_containers" == *$'\n'* ]]; then
    echo 'BLOCKED: найдено несколько PostgreSQL контейнеров проекта safeai' >&2
    exit 1
fi
if [[ -n "$postgres_containers" ]]; then
    container="$postgres_containers"
    image="$(docker inspect --format '{{.Config.Image}}' "$container")"
    running="$(docker inspect --format '{{.State.Running}}' "$container")"
    printf 'Existing PostgreSQL container: YES\nImage: %s\nRunning: %s\n' \
        "$image" "$running"
else
    echo 'Existing PostgreSQL container: NO'
fi
if docker volume inspect safeai_postgres_data >/dev/null 2>&1; then
    echo 'Legacy PostgreSQL volume: PRESENT (never modify in this inventory)'
else
    echo 'Legacy PostgreSQL volume: NOT FOUND'
fi
printf '%s\n' \
    'INVENTORY COMPLETE: read-only inspection; NO production approval.' \
    'Never run docker volume rm, compose down -v, or switch PGDATA in place.'
