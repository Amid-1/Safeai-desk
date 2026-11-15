-- SafeAI-Desk/backend/src/main/resources/db/migration/V87__chat_archive_owner_online_index.sql
-- V87: non-transactional index creation to avoid blocking concurrent writes.
-- Flyway executes this migration outside a transaction (see .sql.conf).
CREATE INDEX CONCURRENTLY ix_chat_archived_owner_v86
  ON public.chat_sessions(organization_id,user_id,archived_at DESC,id DESC)
  WHERE archived_at IS NOT NULL AND erasure_state='AVAILABLE';

-- No IF NOT EXISTS: a broken prior CREATE INDEX CONCURRENTLY may leave an
-- INVALID index with the same name. Abort for explicit DBA recovery instead.
