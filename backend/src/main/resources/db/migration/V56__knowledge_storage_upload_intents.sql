/* Safeai-desk/backend/src/main/resources/db/migration/V56__knowledge_storage_upload_intents.sql */
/* SafeAI Desk V56: durable orphan discovery and fenced cleanup for new uploads.
 * V1-V55 are immutable. No backfill is possible: historical unregistered S3 keys
 * are not conclusively identifiable without an object-store inventory.
 */
DO $$
BEGIN
    IF to_regclass('public.knowledge_document_versions') IS NULL
       OR to_regclass('public.knowledge_bases') IS NULL THEN
        RAISE EXCEPTION 'V56 requires V39 Knowledge versions and V38 bases';
    END IF;
END $$;

CREATE TABLE public.knowledge_storage_upload_intents (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL UNIQUE,
    created_by_user_id uuid NOT NULL,
    storage_key varchar(1024) NOT NULL UNIQUE,
    content_sha256 char(64) NOT NULL,
    content_size_bytes bigint NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'INTENT',
    created_at timestamptz NOT NULL DEFAULT now(),
    stored_at timestamptz,
    linked_at timestamptz,
    reviewed_at timestamptz,
    cleaned_at timestamptz,
    cleanup_token uuid,
    cleanup_claimed_at timestamptz,
    last_error_code varchar(64),
    CONSTRAINT chk_knowledge_storage_upload_intent_state_v56
      CHECK (state IN ('INTENT', 'STORED', 'LINKED', 'CLEANING', 'CLEANED', 'NEEDS_REVIEW')),
    CONSTRAINT chk_knowledge_storage_upload_intent_hash_v56
      CHECK (content_sha256 ~ '^[0-9a-f]{64}$' AND content_size_bytes > 0),
    CONSTRAINT chk_knowledge_storage_upload_intent_dates_v56
      CHECK ((stored_at IS NULL OR stored_at >= created_at)
         AND (linked_at IS NULL OR stored_at IS NOT NULL)
         AND (cleaned_at IS NULL OR stored_at IS NOT NULL)
         AND (cleanup_claimed_at IS NULL OR stored_at IS NOT NULL)),
    CONSTRAINT chk_knowledge_storage_upload_intent_token_v56
      CHECK ((state = 'CLEANING') = (cleanup_token IS NOT NULL)),
    CONSTRAINT chk_knowledge_storage_upload_intent_lifecycle_v56
      CHECK ((state = 'INTENT' AND stored_at IS NULL AND linked_at IS NULL
                              AND cleaned_at IS NULL AND cleanup_claimed_at IS NULL)
          OR (state = 'STORED' AND stored_at IS NOT NULL AND linked_at IS NULL
                               AND cleaned_at IS NULL AND cleanup_claimed_at IS NULL)
          OR (state = 'LINKED' AND stored_at IS NOT NULL AND linked_at IS NOT NULL
                               AND cleaned_at IS NULL AND cleanup_claimed_at IS NULL)
          OR (state = 'CLEANING' AND stored_at IS NOT NULL AND cleaned_at IS NULL
                                 AND linked_at IS NULL AND cleanup_claimed_at IS NOT NULL)
          OR (state = 'CLEANED' AND stored_at IS NOT NULL AND cleaned_at IS NOT NULL
                                AND linked_at IS NULL AND cleanup_claimed_at IS NULL)
          OR (state = 'NEEDS_REVIEW' AND cleaned_at IS NULL AND linked_at IS NULL
                                    AND cleanup_claimed_at IS NULL))
);

CREATE INDEX idx_knowledge_storage_upload_stale_intent_v56
    ON public.knowledge_storage_upload_intents (created_at, id)
    WHERE state = 'INTENT';
CREATE INDEX idx_knowledge_storage_upload_stale_stored_v56
    ON public.knowledge_storage_upload_intents (stored_at, id)
    WHERE state = 'STORED';

CREATE INDEX idx_knowledge_storage_upload_stale_cleanup_v56
    ON public.knowledge_storage_upload_intents (cleanup_claimed_at, id)
    WHERE state = 'CLEANING';

CREATE FUNCTION public.enforce_knowledge_upload_intent_identity_v56()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.knowledge_base_id IS DISTINCT FROM OLD.knowledge_base_id
       OR NEW.document_id IS DISTINCT FROM OLD.document_id
       OR NEW.document_version_id IS DISTINCT FROM OLD.document_version_id
       OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id
       OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
       OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
       OR NEW.content_size_bytes IS DISTINCT FROM OLD.content_size_bytes
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V56 immutable Knowledge upload intent identity changed';
    END IF;
    IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
        (OLD.state='INTENT' AND NEW.state IN ('STORED','NEEDS_REVIEW'))
        OR (OLD.state='STORED' AND NEW.state IN ('LINKED','CLEANING'))
        OR (OLD.state='CLEANING' AND NEW.state IN ('CLEANED','NEEDS_REVIEW'))
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V56 invalid Knowledge upload intent state transition';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_upload_intent_identity_v56
BEFORE UPDATE ON public.knowledge_storage_upload_intents
FOR EACH ROW EXECUTE FUNCTION public.enforce_knowledge_upload_intent_identity_v56();

COMMENT ON TABLE public.knowledge_storage_upload_intents IS
'Durable new-upload intent: uncertain INTENT is quarantined for human review; only positively STORED unlinked objects can be auto-cleaned with fencing. Never infer historical objects are orphaned.';
