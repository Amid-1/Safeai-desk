#!/usr/bin/env bash
# Выполнять только вручную с резервной копией и отдельной ПУСТОЙ БД safeai_restore_*.
set -Eeuo pipefail
: "${SAFEAI_RESTORE_APPROVAL:?Set SAFEAI_RESTORE_APPROVAL=I_ACCEPT_STAGING_RESTORE}"
[[ "$SAFEAI_RESTORE_APPROVAL" == I_ACCEPT_STAGING_RESTORE ]] || exit 1
: "${SAFEAI_RESTORE_DATABASE_URL:?PostgreSQL DSN to disposable target}"
: "${SAFEAI_RESTORE_BACKUP:?Path to a custom-format pg_dump backup}"
: "${SAFEAI_RESTORE_BACKUP_SHA256:?Independent approved SHA256 of backup}"
[[ -f "$SAFEAI_RESTORE_BACKUP" ]] || { echo 'Backup not found' >&2; exit 1; }
command -v pg_restore >/dev/null
command -v psql >/dev/null
actual_hash="$(shasum -a 256 "$SAFEAI_RESTORE_BACKUP" | awk '{print $1}')"
[[ "$actual_hash" == "$SAFEAI_RESTORE_BACKUP_SHA256" ]] || { echo 'Backup hash mismatch' >&2; exit 1; }
dbname="$(psql "$SAFEAI_RESTORE_DATABASE_URL" -X -A -t -v ON_ERROR_STOP=1 -c 'select current_database()')"
[[ "$dbname" =~ ^safeai_restore_[a-zA-Z0-9_]+$ ]] || { echo 'Refusing non-disposable database name' >&2; exit 1; }
# Never restore into an already populated database.
count="$(psql "$SAFEAI_RESTORE_DATABASE_URL" -X -A -t -v ON_ERROR_STOP=1 -c \
  "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'")"
[[ "$count" == 0 ]] || { echo 'Target is not an empty disposable database' >&2; exit 1; }
pg_restore --exit-on-error --no-owner --no-privileges --single-transaction \
  --dbname "$SAFEAI_RESTORE_DATABASE_URL" "$SAFEAI_RESTORE_BACKUP"
failed="$(psql "$SAFEAI_RESTORE_DATABASE_URL" -X -A -t -v ON_ERROR_STOP=1 -c \
  'select count(*) from public.flyway_schema_history where success = false')"
[[ "$failed" == 0 ]] || { echo 'Restore contains failed migrations' >&2; exit 1; }
version="$(psql "$SAFEAI_RESTORE_DATABASE_URL" -X -A -t -v ON_ERROR_STOP=1 -c \
  "select max(version::int) from public.flyway_schema_history where type='SQL' and success")"
[[ "$version" =~ ^[0-9]+$ ]] || { echo 'Could not determine Flyway history version' >&2; exit 1; }
echo "RESTORE-BASELINE PASS: $dbname, Flyway V$version, failed=0"
echo 'NOT YET VERIFIED: S3 tombstones/WORM, application API health, RTO/RPO, replay/erasure invariants.'
