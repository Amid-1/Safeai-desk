#!/usr/bin/env bash
# Destructive recovery on the explicitly selected deployment; NEVER auto-reopen traffic.
set -Eeuo pipefail
[[ "${CONFIRM_RESTORE:-}" == YES && "${SAFEAI_RESTORE_KEEP_OFFLINE:-}" == YES ]] || {
    echo 'BLOCKED: requires CONFIRM_RESTORE=YES and SAFEAI_RESTORE_KEEP_OFFLINE=YES.' >&2
    echo 'Use infra/postgres/rehearse-restore.sh for the first isolated rehearsal.' >&2
    exit 1
}
[[ $# -ge 1 ]] || {
    printf '%s\n' 'Usage: CONFIRM_RESTORE=YES SAFEAI_RESTORE_KEEP_OFFLINE=YES' \
        'restore-postgres.sh <backup.dump.age> [env-file]' >&2
    exit 1
}
backup_file="$1"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
repo_dir="$(cd -- "$infra_dir/.." && pwd)"
env_file="${2:-$repo_dir/backend/.env.prod}"
compose_file="$infra_dir/docker-compose.yml"

for cmd in docker age sha256sum; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "Missing command: $cmd" >&2; exit 1; }
done
[[ -f "$env_file" && ! -L "$env_file" ]] || { echo 'Missing or unsafe deployment env file' >&2; exit 1; }
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a
: "${SAFEAI_BACKUP_AGE_IDENTITY_FILE:?SAFEAI_BACKUP_AGE_IDENTITY_FILE is required}"
[[ -f "$backup_file" && ! -L "$backup_file" && -f "$backup_file.sha256" ]] || {
    echo 'Missing backup or checksum; refusing to restore' >&2; exit 1; }
[[ -f "$SAFEAI_BACKUP_AGE_IDENTITY_FILE" && ! -L "$SAFEAI_BACKUP_AGE_IDENTITY_FILE" ]] || {
    echo 'Missing age identity' >&2; exit 1; }
(cd "$(dirname "$backup_file")" && sha256sum --check "$(basename "$backup_file").sha256")

echo 'STOPPING public-facing services. They will NOT be restarted automatically.'
docker compose --env-file "$env_file" -f "$compose_file" stop nginx backend
# No EXIT trap may restart backend or nginx after ANY outcome, including success.
echo 'Restoring live database from encrypted backup; this is destructive.'
age --decrypt --identity "$SAFEAI_BACKUP_AGE_IDENTITY_FILE" "$backup_file" \
    | docker compose --env-file "$env_file" -f "$compose_file" exec -T postgres \
      sh -euc '
          export PGPASSWORD="$(tr -d "\r\n" < /run/secrets/postgres_bootstrap_password)"
          pg_restore \
            --host=127.0.0.1 \
            --username "$POSTGRES_USER" \
            --dbname "$POSTGRES_DB" \
            --clean --if-exists --exit-on-error \
            --no-owner --no-privileges \
            --role "$SAFEAI_DB_MIGRATOR_USER"
      '

# The bootstrap file is mounted inside the image at /opt/safeai, but the
# necessary DB role credentials exist only in the db-bootstrap service.
# Re-run its one-shot job; never run an absent /docker-entrypoint-initdb.d file.
docker compose --env-file "$env_file" -f "$compose_file" \
    up -d --no-deps --force-recreate db-bootstrap
bootstrap_id="$(docker compose --env-file "$env_file" -f "$compose_file" ps -aq db-bootstrap)"
[[ -n "$bootstrap_id" ]] || { echo 'db-bootstrap job missing' >&2; exit 1; }
exit_code="$(docker wait "$bootstrap_id")"
[[ "$exit_code" == 0 ]] || { echo "db-bootstrap failed ($exit_code)" >&2; exit 1; }
cat <<'NOTICE'
RESTORE COMMAND COMPLETED; APPLICATION REMAINS OFFLINE.
Before restoring service, perform the independent WORM/deletion-journal
reconciliation, validate Flyway and database integrity, check data retention,
review application and search indexes, and explicitly approve reopening traffic.
This script intentionally never calls 'up -d backend nginx'.
NOTICE
