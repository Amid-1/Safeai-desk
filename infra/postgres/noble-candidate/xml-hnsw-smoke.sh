#!/usr/bin/env bash
set -Eeuo pipefail
image="${1:-safeai-postgres:noble-test}"
name="safeai-noble-smoke-$$"
secret="$(openssl rand -hex 24)"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
# --tmpfs for PGDATA: independent from all named volumes and host data.
docker run -d --name "$name" \
  --tmpfs /var/lib/postgresql/data:rw,size=192m,mode=1777 \
  --env POSTGRES_USER=safeai_smoke \
  --env POSTGRES_DB=safeai_smoke \
  --env "POSTGRES_PASSWORD=$secret" \
  "$image" >/dev/null
for i in $(seq 1 75); do
  if docker exec "$name" psql -v ON_ERROR_STOP=1 -U safeai_smoke -d safeai_smoke -tAc "SELECT 1" >/dev/null 2>&1; then
    break
  fi
  if [[ "$i" -eq 75 ]]; then
    docker logs "$name" >&2; echo 'Postgres not ready' >&2; exit 1
  fi
  sleep 1
done

docker exec -i "$name" psql -v ON_ERROR_STOP=1 -U safeai_smoke -d safeai_smoke <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
DO $$BEGIN
  IF (SELECT extversion FROM pg_extension WHERE extname = 'vector') <> '0.8.7' THEN
    RAISE EXCEPTION 'Unexpected pgvector version';
  END IF;
END$$;
SELECT XMLPARSE(DOCUMENT '<root><a>ok</a></root>')::text;
SELECT XMLPARSE(DOCUMENT '<!DOCTYPE root [<!ENTITY a "ok">]><root>&a;</root>')::text;
CREATE TABLE vector_smoke (id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY, embedding vector(3));
INSERT INTO vector_smoke (embedding) VALUES ('[1,0,0]'), ('[0,1,0]'), ('[0,0,1]');
CREATE INDEX vector_smoke_hnsw ON vector_smoke USING hnsw (embedding vector_l2_ops);
SET enable_seqscan=off;
SELECT id FROM vector_smoke ORDER BY embedding <-> '[1,0,0]' LIMIT 1;
SQL
printf 'PASS: fresh init, SQL/XML parser, vector 0.8.7 and HNSW on disposable tmpfs\n'
