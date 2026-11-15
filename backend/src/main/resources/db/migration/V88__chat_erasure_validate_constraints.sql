-- SafeAI-Desk/backend/src/main/resources/db/migration/V88__chat_erasure_validate_constraints.sql
-- V88: explicit validation stage; run during a controlled migration window.
-- NOT VALID in V86 already protects new writes.
ALTER TABLE public.chat_sessions VALIDATE CONSTRAINT chk_chat_erasure_state_v86;

-- V87 must have actually produced a VALID index, not just a catalog name.
DO $index_verify$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i
        JOIN pg_class c ON c.oid=i.indexrelid
        WHERE c.relnamespace='public'::regnamespace
          AND c.relname='ix_chat_archived_owner_v86'
          AND i.indisvalid AND i.indisready
          AND i.indrelid='public.chat_sessions'::regclass
    ) THEN
        RAISE EXCEPTION 'V88: V87 index is missing/invalid; do not launch application';
    END IF;
END $index_verify$;
