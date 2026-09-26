#!/usr/bin/env bash
# SafeAI-Desk/infra/postgres/init/10-create-app-roles.sh
set -Eeuo pipefail

read_secret() {
    local file="$1"

    [[ -r "$file" ]] || {
        echo "Required secret is not readable: $file" >&2
        exit 1
    }

    tr -d '\r\n' < "$file"
}

validate_role_name() {
    local role="$1"

    [[ "$role" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || {
        echo "Unsafe PostgreSQL role name: $role" >&2
        exit 1
    }
}

require_distinct_roles() {
    if [[ "$POSTGRES_USER" == "$SAFEAI_DB_MIGRATOR_USER" \
       || "$POSTGRES_USER" == "$SAFEAI_DB_APP_USER" \
       || "$SAFEAI_DB_MIGRATOR_USER" == "$SAFEAI_DB_APP_USER" ]]; then
        echo "POSTGRES_USER, SAFEAI_DB_MIGRATOR_USER and SAFEAI_DB_APP_USER must be distinct" >&2
        exit 1
    fi
}

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${SAFEAI_DB_MIGRATOR_USER:?SAFEAI_DB_MIGRATOR_USER is required}"
: "${SAFEAI_DB_APP_USER:?SAFEAI_DB_APP_USER is required}"

validate_role_name "$POSTGRES_USER"
validate_role_name "$SAFEAI_DB_MIGRATOR_USER"
validate_role_name "$SAFEAI_DB_APP_USER"
require_distinct_roles

migrator_password="$(read_secret /run/secrets/db_migrator_password)"
app_password="$(read_secret /run/secrets/db_app_password)"

[[ -n "$migrator_password" && -n "$app_password" ]] || {
    echo "Database role passwords must not be empty" >&2
    exit 1
}

psql_command=(
    psql
    --set=ON_ERROR_STOP=1
    --username "$POSTGRES_USER"
    --dbname "$POSTGRES_DB"
    --set=migrator_user="$SAFEAI_DB_MIGRATOR_USER"
    --set=app_user="$SAFEAI_DB_APP_USER"
)

if [[ -n "${POSTGRES_HOST:-}" ]]; then
    bootstrap_password="$(read_secret /run/secrets/postgres_bootstrap_password)"
    [[ -n "$bootstrap_password" ]] || {
        echo "PostgreSQL bootstrap password must not be empty" >&2
        exit 1
    }

    export PGPASSWORD="$bootstrap_password"

    psql_command+=(
        --host "$POSTGRES_HOST"
        --port "${POSTGRES_PORT:-5432}"
    )
fi

export SAFEAI_MIGRATOR_PASSWORD="$migrator_password"
export SAFEAI_APP_PASSWORD="$app_password"

"${psql_command[@]}" <<'SQL'
\getenv migrator_password SAFEAI_MIGRATOR_PASSWORD
\getenv app_password SAFEAI_APP_PASSWORD

-- V41+ uses vector(384). Install the extension while connected as the
-- bootstrap/superuser role. Flyway's later CREATE EXTENSION IF NOT EXISTS
-- then becomes an idempotent no-op for the non-superuser migrator.
CREATE EXTENSION IF NOT EXISTS vector;

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L',
    :'migrator_user',
    :'migrator_password'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'migrator_user'
)
\gexec

SELECT format(
    'ALTER ROLE %I LOGIN PASSWORD %L '
    'NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
    :'migrator_user',
    :'migrator_password'
)
\gexec

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L',
    :'app_user',
    :'app_password'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'app_user'
)
\gexec

SELECT format(
    'ALTER ROLE %I LOGIN PASSWORD %L '
    'NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
    :'app_user',
    :'app_password'
)
\gexec

-- The migration role owns the application database/schema. The runtime role
-- receives only explicit data-plane privileges below.
SELECT format(
    'ALTER DATABASE %I OWNER TO %I',
    current_database(),
    :'migrator_user'
)
\gexec

REVOKE ALL ON SCHEMA public FROM PUBLIC;

SELECT format(
    'ALTER SCHEMA public OWNER TO %I',
    :'migrator_user'
)
\gexec

-- Existing installations can contain objects created by the former bootstrap
-- role. Transfer non-extension application objects to the Flyway migrator.
SELECT format(
    'ALTER TABLE %I.%I OWNER TO %I',
    namespace.nspname,
    relation.relname,
    :'migrator_user'
)
FROM pg_class relation
JOIN pg_namespace namespace
  ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind IN ('r', 'p')
  AND NOT EXISTS (
      SELECT 1
      FROM pg_depend dependency
      WHERE dependency.classid = 'pg_class'::regclass
        AND dependency.objid = relation.oid
        AND dependency.deptype = 'e'
  )
ORDER BY relation.relkind, relation.relname
\gexec

SELECT format(
    'ALTER SEQUENCE %I.%I OWNER TO %I',
    namespace.nspname,
    relation.relname,
    :'migrator_user'
)
FROM pg_class relation
JOIN pg_namespace namespace
  ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind = 'S'
  AND NOT EXISTS (
      SELECT 1
      FROM pg_depend dependency
      WHERE dependency.classid = 'pg_class'::regclass
        AND dependency.objid = relation.oid
        AND dependency.deptype = 'e'
  )
ORDER BY relation.relname
\gexec

SELECT format(
    'ALTER VIEW %I.%I OWNER TO %I',
    namespace.nspname,
    relation.relname,
    :'migrator_user'
)
FROM pg_class relation
JOIN pg_namespace namespace
  ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind = 'v'
  AND NOT EXISTS (
      SELECT 1
      FROM pg_depend dependency
      WHERE dependency.classid = 'pg_class'::regclass
        AND dependency.objid = relation.oid
        AND dependency.deptype = 'e'
  )
ORDER BY relation.relname
\gexec

SELECT format(
    'ALTER MATERIALIZED VIEW %I.%I OWNER TO %I',
    namespace.nspname,
    relation.relname,
    :'migrator_user'
)
FROM pg_class relation
JOIN pg_namespace namespace
  ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind = 'm'
  AND NOT EXISTS (
      SELECT 1
      FROM pg_depend dependency
      WHERE dependency.classid = 'pg_class'::regclass
        AND dependency.objid = relation.oid
        AND dependency.deptype = 'e'
  )
ORDER BY relation.relname
\gexec

SELECT format(
    'ALTER FOREIGN TABLE %I.%I OWNER TO %I',
    namespace.nspname,
    relation.relname,
    :'migrator_user'
)
FROM pg_class relation
JOIN pg_namespace namespace
  ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'public'
  AND relation.relkind = 'f'
  AND NOT EXISTS (
      SELECT 1
      FROM pg_depend dependency
      WHERE dependency.classid = 'pg_class'::regclass
        AND dependency.objid = relation.oid
        AND dependency.deptype = 'e'
  )
ORDER BY relation.relname
\gexec

SELECT format(
    'ALTER %s %I.%I(%s) OWNER TO %I',
    CASE routine.prokind
        WHEN 'p' THEN 'PROCEDURE'
        WHEN 'a' THEN 'AGGREGATE'
        ELSE 'FUNCTION'
    END,
    namespace.nspname,
    routine.proname,
    pg_get_function_identity_arguments(routine.oid),
    :'migrator_user'
)
FROM pg_proc routine
JOIN pg_namespace namespace
  ON namespace.oid = routine.pronamespace
WHERE namespace.nspname = 'public'
  AND NOT EXISTS (
      SELECT 1
      FROM pg_depend dependency
      WHERE dependency.classid = 'pg_proc'::regclass
        AND dependency.objid = routine.oid
        AND dependency.deptype = 'e'
  )
ORDER BY routine.proname, routine.oid
\gexec

SELECT format(
    'ALTER TYPE %I.%I OWNER TO %I',
    namespace.nspname,
    type_entry.typname,
    :'migrator_user'
)
FROM pg_type type_entry
JOIN pg_namespace namespace
  ON namespace.oid = type_entry.typnamespace
WHERE namespace.nspname = 'public'
  AND type_entry.typtype IN ('e', 'r', 'm')
  AND type_entry.typrelid = 0
  AND NOT EXISTS (
      SELECT 1
      FROM pg_depend dependency
      WHERE dependency.classid = 'pg_type'::regclass
        AND dependency.objid = type_entry.oid
        AND dependency.deptype = 'e'
  )
ORDER BY type_entry.typname
\gexec

SELECT format(
    'ALTER DOMAIN %I.%I OWNER TO %I',
    namespace.nspname,
    type_entry.typname,
    :'migrator_user'
)
FROM pg_type type_entry
JOIN pg_namespace namespace
  ON namespace.oid = type_entry.typnamespace
WHERE namespace.nspname = 'public'
  AND type_entry.typtype = 'd'
  AND NOT EXISTS (
      SELECT 1
      FROM pg_depend dependency
      WHERE dependency.classid = 'pg_type'::regclass
        AND dependency.objid = type_entry.oid
        AND dependency.deptype = 'e'
  )
ORDER BY type_entry.typname
\gexec

-- Remove implicit database access and restore only the capabilities used by
-- SafeAI. Migrator may use TEMPORARY for future controlled migrations; runtime
-- gets CONNECT only.
SELECT format(
    'REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC',
    current_database()
)
\gexec

SELECT format(
    'GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I',
    current_database(),
    :'migrator_user'
)
\gexec

SELECT format(
    'GRANT CONNECT ON DATABASE %I TO %I',
    current_database(),
    :'app_user'
)
\gexec

SELECT format(
    'GRANT USAGE, CREATE ON SCHEMA public TO %I',
    :'migrator_user'
)
\gexec

SELECT format(
    'GRANT USAGE ON SCHEMA public TO %I',
    :'app_user'
)
\gexec

SELECT format(
    'REVOKE CREATE ON SCHEMA public FROM %I',
    :'app_user'
)
\gexec

-- Runtime DML is broad at schema level because the current application uses a
-- single DB role. RLS/tenant isolation remains an application/schema invariant.
SELECT format(
    'GRANT SELECT, INSERT, UPDATE, DELETE '
    'ON ALL TABLES IN SCHEMA public TO %I',
    :'app_user'
)
\gexec

SELECT format(
    'GRANT USAGE, SELECT, UPDATE '
    'ON ALL SEQUENCES IN SCHEMA public TO %I',
    :'app_user'
)
\gexec

-- PostgreSQL grants EXECUTE on functions to PUBLIC by default. Remove that
-- implicit surface, then grant only the application role explicitly.
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;

SELECT format(
    'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO %I',
    :'migrator_user'
)
\gexec

SELECT format(
    'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO %I',
    :'app_user'
)
\gexec

SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
    'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
    :'migrator_user',
    :'app_user'
)
\gexec

SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
    'GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I',
    :'migrator_user',
    :'app_user'
)
\gexec

SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
    'REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC',
    :'migrator_user'
)
\gexec

SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
    'GRANT EXECUTE ON FUNCTIONS TO %I',
    :'migrator_user',
    :'app_user'
)
\gexec

SELECT format(
    'ALTER ROLE %I SET search_path = public',
    :'migrator_user'
)
\gexec

SELECT format(
    'ALTER ROLE %I SET search_path = public',
    :'app_user'
)
\gexec
SQL

unset SAFEAI_MIGRATOR_PASSWORD SAFEAI_APP_PASSWORD PGPASSWORD bootstrap_password
