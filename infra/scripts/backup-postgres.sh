#!/usr/bin/env bash
set -Eeuo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
repo_dir="$(cd -- "$infra_dir/.." && pwd)"
env_file="${1:-$repo_dir/backend/.env.prod}"
compose_file="$infra_dir/docker-compose.yml"

for cmd in docker age sha256sum rclone; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "Required command is missing: $cmd" >&2; exit 1; }
done

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

: "${SAFEAI_BACKUP_LOCAL_DIR:?SAFEAI_BACKUP_LOCAL_DIR is required}"
: "${SAFEAI_BACKUP_AGE_RECIPIENT:?SAFEAI_BACKUP_AGE_RECIPIENT is required}"
: "${SAFEAI_BACKUP_REMOTE:?SAFEAI_BACKUP_REMOTE is required}"
: "${SAFEAI_BACKUP_RETENTION_DAYS:?SAFEAI_BACKUP_RETENTION_DAYS is required}"
[[ "$SAFEAI_BACKUP_AGE_RECIPIENT" != *REPLACE_WITH* ]] || { echo "Set a real age recipient" >&2; exit 1; }

umask 077
install -d -m 0700 "$SAFEAI_BACKUP_LOCAL_DIR"
timestamp="$(date -u +'%Y%m%dT%H%M%SZ')"
backup_name="safeai-${POSTGRES_DB}-${timestamp}.dump.age"
backup_file="$SAFEAI_BACKUP_LOCAL_DIR/$backup_name"
checksum_file="$backup_file.sha256"

docker compose --env-file "$env_file" -f "$compose_file" exec -T postgres \
    sh -euc '
        export PGPASSWORD="$(tr -d "\r\n" < /run/secrets/postgres_bootstrap_password)"
        pg_dump \
          --host=127.0.0.1 \
          --username "$POSTGRES_USER" \
          --dbname "$POSTGRES_DB" \
          --format=custom \
          --compress=9
    ' \
    | age --recipient "$SAFEAI_BACKUP_AGE_RECIPIENT" --output "$backup_file"

sha256sum "$backup_file" > "$checksum_file"
rclone copyto "$backup_file" "${SAFEAI_BACKUP_REMOTE%/}/$backup_name"
rclone copyto "$checksum_file" "${SAFEAI_BACKUP_REMOTE%/}/$(basename "$checksum_file")"
find "$SAFEAI_BACKUP_LOCAL_DIR" -type f \
    \( -name '*.dump.age' -o -name '*.dump.age.sha256' \) \
    -mtime "+$SAFEAI_BACKUP_RETENTION_DAYS" -delete

echo "Encrypted backup created and uploaded: $backup_file"
