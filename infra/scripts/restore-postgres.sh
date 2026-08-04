#!/usr/bin/env bash
set -Eeuo pipefail
[[ "${CONFIRM_RESTORE:-}" == "YES" ]] || { echo "Restore is destructive. Run with CONFIRM_RESTORE=YES." >&2; exit 1; }
[[ $# -ge 1 ]] || { echo "Usage: CONFIRM_RESTORE=YES $0 <backup.dump.age> [env-file]" >&2; exit 1; }
backup_file="$1"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
repo_dir="$(cd -- "$infra_dir/.." && pwd)"
env_file="${2:-$repo_dir/backend/.env.prod}"
compose_file="$infra_dir/docker-compose.yml"

for cmd in docker age sha256sum; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "Required command is missing: $cmd" >&2; exit 1; }
done

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a
: "${SAFEAI_BACKUP_AGE_IDENTITY_FILE:?SAFEAI_BACKUP_AGE_IDENTITY_FILE is required}"
[[ -f "$backup_file" ]] || { echo "Backup file does not exist: $backup_file" >&2; exit 1; }

if [[ -f "$backup_file.sha256" ]]; then
    (cd "$(dirname "$backup_file")" && sha256sum --check "$(basename "$backup_file").sha256")
fi

docker compose --env-file "$env_file" -f "$compose_file" stop nginx backend
cleanup() {
    docker compose --env-file "$env_file" -f "$compose_file" up -d backend nginx
}
trap cleanup EXIT

age --decrypt --identity "$SAFEAI_BACKUP_AGE_IDENTITY_FILE" "$backup_file" \
    | docker compose --env-file "$env_file" -f "$compose_file" exec -T postgres \
      sh -euc '
          export PGPASSWORD="$(tr -d "\r\n" < /run/secrets/postgres_bootstrap_password)"
          pg_restore \
            --host=127.0.0.1 \
            --username "$POSTGRES_USER" \
            --dbname "$POSTGRES_DB" \
            --clean \
            --if-exists \
            --no-owner \
            --no-privileges \
            --role "$SAFEAI_DB_MIGRATOR_USER"
      '

docker compose --env-file "$env_file" -f "$compose_file" exec -T postgres \
    bash /docker-entrypoint-initdb.d/10-create-app-roles.sh

echo "Restore completed. Backend and Nginx will be started."
