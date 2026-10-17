-- SafeAI-Desk/backend/src/main/resources/db/migration/V76__knowledge_retrieval_query_privacy.sql
-- SafeAI Desk V76: fail-closed privacy for NEW Knowledge retrieval runs.
-- Existing immutable V41/V44 rows are deliberately preserved byte-for-byte.
-- Apply only after prior versions V1..V75 are committed to Flyway history.
-- Never edit V41/V44/V55/V63. This NOT VALID constraint is enforced for
-- INSERT/UPDATE after installation; historical plaintext must be reviewed
-- under a separately approved retention and evidence migration.

DO $preflight$
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NULL
       OR NOT EXISTS (
           SELECT 1 FROM public.flyway_schema_history
           WHERE version = '75' AND success
       ) THEN
        RAISE EXCEPTION 'V76 requires applied Flyway V75; do not skip V73–V75';
    END IF;
    IF to_regclass('public.knowledge_retrieval_runs') IS NULL
       OR to_regclass('public.knowledge_retrieval_hits') IS NULL THEN
        RAISE EXCEPTION 'V76: Knowledge V41 retrieval schema is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgrelid='public.knowledge_retrieval_runs'::regclass
          AND tgname='trg_knowledge_retrieval_runs_immutable_v44'
          AND tgenabled IN ('O','A')
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V76: retrieval provenance immutability guard is missing';
    END IF;
END;
$preflight$;

ALTER TABLE public.knowledge_retrieval_runs
    ADD CONSTRAINT chk_knowledge_retrieval_query_redacted_v76
    CHECK (query_text = '[REDACTED:SHA256_ONLY]') NOT VALID;

COMMENT ON CONSTRAINT chk_knowledge_retrieval_query_redacted_v76
ON public.knowledge_retrieval_runs IS
'Enforced on all new writes: never store raw Knowledge search text; query_sha256 preserves lookup evidence. Historical V44-immutable rows are retained for separately authorized privacy remediation.';

-- Review backlog counting is now a production metric; index the review queue
-- without broadening automatic cleanup privileges.
CREATE INDEX idx_knowledge_upload_needs_review_v76
    ON public.knowledge_storage_upload_intents (needs_review_at, id)
    WHERE state = 'NEEDS_REVIEW';
