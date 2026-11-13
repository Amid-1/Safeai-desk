-- Read-only source database settings. Run with psql -X -At -F $'\t'.
-- Keep file in trusted, non-repository storage. Never upload to CI.
\set ON_ERROR_STOP on
SELECT pg_encoding_to_char(encoding),
       datcollate,
       datctype,
       datlocprovider,
       COALESCE(daticulocale, ''),
       COALESCE(datcollversion, '')
FROM pg_database
WHERE datname = current_database();
