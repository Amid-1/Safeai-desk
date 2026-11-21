-- Read-only inventory on the SOURCE database.
-- Counts must be captured from the SAME consistent snapshot as the pg_dump;
-- if writes continue independently, a comparison is NOT evidence of equality.
\set ON_ERROR_STOP on
-- Invoke using: psql -X -q -A -t -F $'\t' -v ON_ERROR_STOP=1 -f source-row-counts.sql
SELECT format('SELECT %L, COUNT(*)::bigint FROM %I.%I;', n.nspname || '.' || c.relname, n.nspname, c.relname)
FROM pg_class c
JOIN pg_namespace n ON c.relnamespace=n.oid
WHERE c.relkind IN ('r','p') AND NOT c.relispartition
  AND n.nspname NOT IN ('pg_catalog','information_schema')
  AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY n.nspname,c.relname
\gexec
