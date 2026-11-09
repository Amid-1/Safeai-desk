#!/usr/bin/env bash
# Только изолированная репетиция. Никаких docker compose и production volumes.
set -Eeuo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$script_dir/../.." && pwd)"
image='' archive='' identity='' report_dir='' source_counts=''
usage() {
    cat <<'HELP'
Usage:
  bash infra/postgres/rehearse-restore.sh \
    --image 'ghcr.io/OWNER/safeai-postgres@sha256:<64hex>' \
    --backup /private/path/safeai-....dump.age \
    --identity /private/path/age-identity.txt \
    --report-dir /private/path/restore-report \
    [--source-counts /private/path/source-row-counts.tsv]

Isolation: --network none, NEW temporary volume, no published ports,
no production env/secrets, delete test container/volume on exit.
Requires: docker, age, python3, and a .sha256 file next to the backup.
Does not certify production. Does not change image approval in GitHub.
HELP
}
while (($#)); do
    case "$1" in
        --image|--backup|--identity|--report-dir|--source-counts)
            (($# >= 2)) || { usage >&2; exit 2; }
            case "$1" in
                --image) image="$2" ;; --backup) archive="$2" ;;
                --identity) identity="$2" ;; --report-dir) report_dir="$2" ;;
                --source-counts) source_counts="$2" ;;
            esac
            shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unexpected argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[[ "$image" =~ ^ghcr[.]io/[a-z0-9.-]+/safeai-postgres@sha256:[0-9a-f]{64}$ ]] || {
    echo 'ERROR: image must be an immutable GHCR safeai-postgres digest' >&2; exit 2; }
[[ -f "$archive" && ! -L "$archive" && "$archive" == *.dump.age ]] || {
    echo 'ERROR: backup must be an existing regular *.dump.age file' >&2; exit 2; }
[[ -f "$archive.sha256" && ! -L "$archive.sha256" ]] || {
    echo 'ERROR: backup .sha256 file required' >&2; exit 2; }
[[ -f "$identity" && ! -L "$identity" ]] || {
    echo 'ERROR: age identity file must be a regular file' >&2; exit 2; }
[[ -n "$report_dir" && "$report_dir" != / && ! -e "$report_dir" ]] || {
    echo 'ERROR: --report-dir must refer to a NEW directory' >&2; exit 2; }
if [[ -n "$source_counts" ]]; then
    [[ -f "$source_counts" && ! -L "$source_counts" ]] || {
        echo 'ERROR: source-counts must be a regular file' >&2; exit 2; }
fi
for cmd in docker age python3 diff; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "Missing command: $cmd" >&2; exit 2; }
done
python3 - "$archive" "$archive.sha256" <<'PY'
import hashlib, pathlib, re, sys
archive, checksum = map(pathlib.Path, sys.argv[1:])
expected = checksum.read_text('utf-8').split()[0]
if not re.fullmatch(r'[a-fA-F0-9]{64}', expected):
    raise SystemExit('Invalid backup SHA256 file')
sha = hashlib.sha256()
with archive.open('rb') as source:
    for block in iter(lambda: source.read(1024 * 1024), b''):
        sha.update(block)
if sha.hexdigest() != expected.lower():
    raise SystemExit('Backup SHA256 mismatch')
print('PASS: encrypted backup SHA256')
PY

install -d -m 0700 "$report_dir"
marker="$(date -u +'%Y%m%dT%H%M%SZ')-$RANDOM-$RANDOM"
container="safeai-restore-drill-$marker"
volume="safeai_restore_drill_$marker"
volume_created=0
container_created=0
cleanup() {
    local status=$?
    local cleanup_failed=0
    local label=''
    trap - EXIT

    if ((container_created)); then
        if ! docker rm -f "$container" >/dev/null 2>&1; then
            printf 'SECURITY ALERT: test container might remain: %s\n' "$container" >&2
            cleanup_failed=1
        fi
    fi
    if ((volume_created)); then
        label="$(docker volume inspect \
            --format '{{ index .Labels "io.safeai.rehearsal" }}' \
            "$volume" 2>/dev/null || true)"
        if [[ "$label" == true ]]; then
            if ! docker volume rm "$volume" >/dev/null 2>&1; then
                printf 'SECURITY ALERT: sensitive test volume remains: %s\n' "$volume" >&2
                cleanup_failed=1
            fi
        elif docker volume inspect "$volume" >/dev/null 2>&1; then
            printf 'SECURITY ALERT: volume label mismatch; not deleting %s\n' "$volume" >&2
            cleanup_failed=1
        fi
    fi

    if ((cleanup_failed)); then
        status=1
        printf 'MANUAL CLEANUP REQUIRED: investigate container=%s volume=%s\n' \
            "$container" "$volume" >&2
        printf 'cleanup_status=FAILED; container=%s; volume=%s\n' \
            "$container" "$volume" > "$report_dir/cleanup-status.txt" || true
    elif ((status == 0)); then
        printf 'cleanup_status=VERIFIED\n' > "$report_dir/cleanup-status.txt"
        printf 'REHEARSAL TECHNICAL PASS; clean temporary resources; NOT PRODUCTION APPROVAL: %s\n' \
            "$report_dir"
    fi
    if ((status != 0)); then
        printf 'REHEARSAL FAILED (exit=%s); production unchanged; report: %s\n' \
            "$status" "$report_dir" >&2
    fi
    exit "$status"
}
trap cleanup EXIT

docker volume inspect "$volume" >/dev/null 2>&1 && {
    echo 'ERROR: temporary volume already exists; refusing to reuse it' >&2
    exit 1
}
docker container inspect "$container" >/dev/null 2>&1 && {
    echo 'ERROR: temporary container already exists; refusing to reuse it' >&2; exit 1; }

docker pull "$image" >/dev/null
# Confirm the pinned reference was actually pulled; no local mutable tag accepted.
docker image inspect "$image" >/dev/null

docker volume create --label io.safeai.rehearsal=true "$volume" >/dev/null
volume_created=1
# Trust applies ONLY inside a container with NO network and NO published ports.
# No production credentials are passed to the container.
docker run -d --name "$container" --network none \
    --label io.safeai.rehearsal=true \
    --volume "$volume:/var/lib/postgresql/data" \
    --env POSTGRES_USER=postgres \
    --env POSTGRES_DB=safeai_rehearsal \
    --env POSTGRES_HOST_AUTH_METHOD=trust \
    "$image" >/dev/null
container_created=1
ready=0
for _ in {1..90}; do
    if docker exec "$container" pg_isready -U postgres -d safeai_rehearsal >/dev/null 2>&1; then
        ready=1; break
    fi
    sleep 1
done
((ready)) || { docker logs "$container" >&2 || true; echo 'PostgreSQL not ready' >&2; exit 1; }

echo 'Restoring encrypted custom-format dump into disposable database...'
# age stdout streams directly into pg_restore, NEVER written as plaintext to host disk.
age --decrypt --identity "$identity" "$archive" \
    | docker exec -i "$container" pg_restore -U postgres -d safeai_rehearsal \
        --no-owner --no-privileges --exit-on-error --single-transaction

echo 'Running read-only metadata audit...'
docker exec -i "$container" psql -X -q -v ON_ERROR_STOP=1 \
    -U postgres -d safeai_rehearsal \
    < "$repo_dir/infra/postgres/restore-audit.sql" \
    > "$report_dir/metadata-audit.txt"

query() {
    docker exec "$container" psql -X -A -t -v ON_ERROR_STOP=1 -U postgres -d safeai_rehearsal -c "$1"
}
check_num() {
    local label="$1" query_text="$2" expected="$3" actual
    actual="$(query "$query_text")"
    [[ "$actual" == "$expected" ]] || {
        echo "FAIL: $label expected=$expected actual=$actual" >&2; exit 1; }
    echo "PASS: $label = $expected"
}
check_num 'PostgreSQL 16' \
    "SELECT CASE WHEN current_setting('server_version_num')::int
            BETWEEN 160000 AND 169999 THEN 1 ELSE 0 END" 1
check_num 'pgvector 0.8.7' \
    "SELECT COUNT(*) FROM pg_extension WHERE extname='vector' AND extversion='0.8.7'" 1
check_num 'Invalid indexes' \
    'SELECT COUNT(*) FROM pg_index WHERE NOT indisvalid OR NOT indisready' 0
check_num 'Unvalidated constraints' \
    'SELECT COUNT(*) FROM pg_constraint WHERE NOT convalidated' 0
check_num 'Database default collation version mismatch' \
    'SELECT COUNT(*) FROM pg_database
     WHERE datname=current_database()
       AND datcollversion IS DISTINCT FROM pg_database_collation_actual_version(oid)' 0
check_num 'Explicit collation version mismatch' \
    'SELECT COUNT(*) FROM pg_collation
     WHERE collversion IS NOT NULL
       AND collversion IS DISTINCT FROM pg_collation_actual_version(oid)' 0
check_num 'Flyway history table present' \
    "SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NOT NULL THEN 1 ELSE 0 END" 1
check_num 'Failed Flyway migrations' \
    'SELECT COUNT(*) FROM public.flyway_schema_history WHERE NOT success' 0

# Exact coverage of the committed Flyway versions. No migrations run on the restored copy.
expected_versions="$report_dir/expected-flyway-versions.txt"
applied_versions="$report_dir/applied-flyway-versions.txt"
python3 - "$repo_dir/backend/src/main/resources/db/migration" "$expected_versions" <<'PY'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
versions = set()
for path in root.glob('V*.sql'):
    match = re.fullmatch(r'V([1-9][0-9]*)__[A-Za-z0-9_-]+\.sql', path.name)
    if not match:
        raise SystemExit(f'Unrecognized Flyway migration: {path.name}')
    versions.add(int(match.group(1)))
if not versions or versions != set(range(1, max(versions) + 1)):
    raise SystemExit('Flyway source versions have gaps')
pathlib.Path(sys.argv[2]).write_text(''.join(f'{v}\n' for v in sorted(versions)), encoding='utf-8')
PY
query 'SELECT version FROM public.flyway_schema_history
       WHERE success AND version IS NOT NULL
       ORDER BY version::int' > "$applied_versions"
diff -u "$expected_versions" "$applied_versions" || {
    echo 'FAILED: restored Flyway versions differ from repository migrations' >&2; exit 1; }
echo 'PASS: committed Flyway version inventory matches restored schema history'

# Streaming exact row-count inventory of every ordinary/partitioned non-system table.
# Use a fresh test database; a source-side snapshot manifest is REQUIRED for comparison.
docker exec -i "$container" psql -X -q -A -t -F $'\t' \
    -v ON_ERROR_STOP=1 -U postgres -d safeai_rehearsal \
    > "$report_dir/restored-row-counts.tsv" <<'SQL'
SELECT format('SELECT %L, COUNT(*)::bigint FROM %I.%I;', n.nspname || '.' || c.relname, n.nspname, c.relname)
FROM pg_class c
JOIN pg_namespace n ON c.relnamespace=n.oid
WHERE c.relkind IN ('r','p') AND NOT c.relispartition
  AND n.nspname NOT IN ('pg_catalog','information_schema')
  AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY n.nspname,c.relname
\gexec
SQL
LC_ALL=C sort -o "$report_dir/restored-row-counts.tsv" "$report_dir/restored-row-counts.tsv"
if [[ -n "$source_counts" ]]; then
    diff -u "$source_counts" "$report_dir/restored-row-counts.tsv" > "$report_dir/source-count-diff.txt" || {
        echo 'FAILED: source and restored table counts differ. See source-count-diff.txt' >&2; exit 1; }
    echo 'PASS: source table row counts match restored copy'
else
    echo 'NOT VERIFIED: no consistent source-side row count manifest was provided.'
fi
cat > "$report_dir/RESULT.txt" <<EOF
TECHNICAL RESTORE COMPLETED ON ISOLATED NEW DOCKER VOLUME
image=$image
backup_sha256=$(python3 - "$archive" <<'PY'
import hashlib, pathlib, sys
sha=hashlib.sha256()
with pathlib.Path(sys.argv[1]).open('rb') as f:
    for block in iter(lambda:f.read(1024*1024),b''): sha.update(block)
print(sha.hexdigest())
PY
)
source_row_count_comparison=$([[ -n "$source_counts" ]] && echo passed || echo NOT_DONE)
Production release approval=NOT_GRANTED
Remaining requirements: source manifest at SAME snapshot, Flyway validate with application,
SQL/XML/RAG business checks, DBA collation review, backup retention and WORM restore gate.
Temporary cleanup is mandatory. See cleanup-status.txt after this process exits.
EOF
cat "$report_dir/RESULT.txt"
