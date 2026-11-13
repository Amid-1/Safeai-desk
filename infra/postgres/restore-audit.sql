-- Read-only metadata audit on an ISOLATED RESTORED copy only.
-- This script DOES NOT perform REINDEX, ALTER DATABASE or Flyway migrate.
\set ON_ERROR_STOP on
\echo === PostgreSQL version and locale ===
SELECT current_setting('server_version') AS server_version,
       current_setting('lc_collate') AS lc_collate,
       current_setting('lc_ctype') AS lc_ctype;
\echo === Database collation versions (mismatch requires careful index rebuild) ===
SELECT datname, datlocprovider, datcollversion AS recorded,
       pg_database_collation_actual_version(oid) AS actual,
       datcollversion IS DISTINCT FROM pg_database_collation_actual_version(oid) AS differs
FROM pg_database WHERE datname = current_database();
\echo === Explicit collations with changed versions ===
SELECT n.nspname AS schema_name, c.collname,
       c.collprovider, c.collversion AS recorded,
       pg_collation_actual_version(c.oid) AS actual
FROM pg_collation c
JOIN pg_namespace n ON n.oid = c.collnamespace
WHERE c.collversion IS NOT NULL
  AND c.collversion IS DISTINCT FROM pg_collation_actual_version(c.oid)
ORDER BY 1, 2;
\echo === Installed vector and other extensions ===
SELECT extname, extversion FROM pg_extension ORDER BY 1;
\echo === Invalid or not-ready indexes ===
SELECT idx.indexrelid::regclass AS index_name, idx.indisvalid, idx.indisready
FROM pg_index idx
WHERE NOT idx.indisvalid OR NOT idx.indisready
ORDER BY 1;
\echo === HNSW indexes ===
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes
WHERE indexdef ILIKE '%USING hnsw%'
ORDER BY 1,2,3;
\echo === Flyway history table (check manually if not in public) ===
SELECT to_regclass('public.flyway_schema_history') AS flyway_history;
