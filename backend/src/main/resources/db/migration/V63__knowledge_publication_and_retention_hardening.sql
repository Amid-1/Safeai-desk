-- backend/src/main/resources/db/migration/V63__knowledge_publication_and_retention_hardening.sql
/*
 * SafeAI Desk V63
 * Knowledge publication/retention hardening after V57.
 *
 * Applied migrations V1..V62 are immutable. This migration is additive and:
 *   - separates latest uploaded version from the searchable published version;
 *   - atomically advances the searchable pointer only after fenced READY publication;
 *   - fixes generation protection to follow the searchable pointer;
 *   - hardens generation INSERT/lifecycle chronology;
 *   - separates "needs review" time from actual review time for upload intents.
 */

DO $$
BEGIN
    IF to_regclass('public.knowledge_documents') IS NULL
       OR to_regclass('public.knowledge_document_versions') IS NULL
       OR to_regclass('public.knowledge_ingestion_jobs') IS NULL
       OR to_regclass('public.knowledge_index_generations') IS NULL
       OR to_regclass('public.knowledge_storage_upload_intents') IS NULL THEN
        RAISE EXCEPTION 'V63 requires the V57 Knowledge schema';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. Latest uploaded != published/searchable.
-- ---------------------------------------------------------------------------

ALTER TABLE public.knowledge_documents
    ADD COLUMN searchable_version_id uuid;

/*
 * Backfill with the newest document version that has an ACTIVE published
 * generation. This is intentionally not copied blindly from current_version_id:
 * current may already point at PENDING/FAILED upload.
 */
UPDATE public.knowledge_documents document
SET searchable_version_id = (
    SELECT version.id
    FROM public.knowledge_document_versions version
    JOIN public.knowledge_index_generations generation
      ON generation.document_version_id = version.id
     AND generation.document_id = version.document_id
     AND generation.knowledge_base_id = version.knowledge_base_id
     AND generation.organization_id = version.organization_id
     AND generation.state = 'ACTIVE'
    WHERE version.document_id = document.id
      AND version.knowledge_base_id = document.knowledge_base_id
      AND version.organization_id = document.organization_id
    ORDER BY version.version_number DESC, generation.published_at DESC,
             generation.index_generation DESC
    LIMIT 1
)
WHERE document.searchable_version_id IS NULL
  AND EXISTS (
      SELECT 1
      FROM public.knowledge_document_versions version
      JOIN public.knowledge_index_generations generation
        ON generation.document_version_id = version.id
       AND generation.document_id = version.document_id
       AND generation.knowledge_base_id = version.knowledge_base_id
       AND generation.organization_id = version.organization_id
       AND generation.state = 'ACTIVE'
      WHERE version.document_id = document.id
        AND version.knowledge_base_id = document.knowledge_base_id
        AND version.organization_id = document.organization_id
  );

ALTER TABLE public.knowledge_documents
    ADD CONSTRAINT fk_knowledge_documents_searchable_version_v63
    FOREIGN KEY (
        searchable_version_id,
        id,
        knowledge_base_id,
        organization_id
    )
    REFERENCES public.knowledge_document_versions (
        id,
        document_id,
        knowledge_base_id,
        organization_id
    )
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_knowledge_documents_searchable_version_v63
    ON public.knowledge_documents (
        organization_id,
        knowledge_base_id,
        searchable_version_id
    )
    WHERE searchable_version_id IS NOT NULL;

COMMENT ON COLUMN public.knowledge_documents.current_version_id IS
'Latest uploaded version. It may be PENDING/processing/FAILED and is not the retrieval publication pointer.';

COMMENT ON COLUMN public.knowledge_documents.searchable_version_id IS
'Latest successfully published searchable version. Advanced atomically only after a fenced READY generation publication.';

-- ---------------------------------------------------------------------------
-- 2. Searchable pointer may reference only the exact ACTIVE published generation
--    currently owned by that document-version ingestion record.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.guard_searchable_knowledge_version_v63()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- OLD is not available for INSERT, so compare old/new only in UPDATE mode.
    IF TG_OP = 'UPDATE' THEN
        IF NEW.searchable_version_id IS NOT DISTINCT FROM OLD.searchable_version_id THEN
            RETURN NEW;
        END IF;
    END IF;

    IF NEW.searchable_version_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.knowledge_ingestion_jobs job
        JOIN public.knowledge_index_generations generation
          ON generation.document_version_id = job.document_version_id
         AND generation.index_generation = job.index_generation
         AND generation.document_id = job.document_id
         AND generation.knowledge_base_id = job.knowledge_base_id
         AND generation.organization_id = job.organization_id
        WHERE job.document_version_id = NEW.searchable_version_id
          AND job.document_id = NEW.id
          AND job.knowledge_base_id = NEW.knowledge_base_id
          AND job.organization_id = NEW.organization_id
          AND job.status = 'READY'
          AND generation.state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: searchable Knowledge version must have the exact ACTIVE READY generation';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_searchable_knowledge_version_insert_v63
BEFORE INSERT
ON public.knowledge_documents
FOR EACH ROW
EXECUTE FUNCTION public.guard_searchable_knowledge_version_v63();

CREATE TRIGGER trg_searchable_knowledge_version_update_v63
BEFORE UPDATE OF searchable_version_id
ON public.knowledge_documents
FOR EACH ROW
EXECUTE FUNCTION public.guard_searchable_knowledge_version_v63();

-- ---------------------------------------------------------------------------
-- 3. Publish generation + searchable pointer atomically.
--    Function name is retained because the V57 trigger already references it.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.publish_knowledge_generation_v57()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_chunk_count bigint;
    v_retired_rows integer;
BEGIN
    IF NEW.index_generation IS NOT DISTINCT FROM OLD.index_generation THEN
        RETURN NEW;
    END IF;

    IF OLD.status <> 'CHUNKING'
       OR OLD.processing_token IS DISTINCT FROM NEW.index_generation
       OR OLD.lease_until IS NULL
       OR OLD.lease_until < clock_timestamp()
       OR NEW.status <> 'READY' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: stale Knowledge generation publication';
    END IF;

    SELECT count(*)
    INTO v_chunk_count
    FROM public.knowledge_document_chunks chunk
    WHERE chunk.document_version_id = NEW.document_version_id
      AND chunk.index_generation = NEW.index_generation;

    IF v_chunk_count <> NEW.chunk_count
       OR EXISTS (
            SELECT 1
            FROM public.knowledge_document_chunks chunk
            WHERE chunk.document_version_id = NEW.document_version_id
              AND chunk.index_generation = NEW.index_generation
              AND (
                    chunk.document_id IS DISTINCT FROM NEW.document_id
                    OR chunk.knowledge_base_id IS DISTINCT FROM NEW.knowledge_base_id
                    OR chunk.organization_id IS DISTINCT FROM NEW.organization_id
                    OR chunk.extractor_version IS DISTINCT FROM NEW.extractor_version
                    OR chunk.chunker_version IS DISTINCT FROM NEW.chunker_version
                    OR chunk.embedding_model IS DISTINCT FROM NEW.embedding_model
              )
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: Knowledge publication metadata mismatch';
    END IF;

    /* Retire the previous reindex generation of this exact version. */
    UPDATE public.knowledge_index_generations generation
    SET state = 'RETIRED',
        retired_at = clock_timestamp()
    WHERE generation.document_version_id = OLD.document_version_id
      AND generation.index_generation = OLD.index_generation
      AND generation.state = 'ACTIVE';

    GET DIAGNOSTICS v_retired_rows = ROW_COUNT;

    IF v_retired_rows = 0
       AND EXISTS (
            SELECT 1
            FROM public.knowledge_index_generations generation
            WHERE generation.document_version_id = NEW.document_version_id
              AND generation.state = 'ACTIVE'
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: ACTIVE generation does not match previous ingestion pointer';
    END IF;

    IF OLD.lease_until < clock_timestamp() THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: ingestion lease expired while publishing';
    END IF;

    INSERT INTO public.knowledge_index_generations (
        document_version_id,
        index_generation,
        document_id,
        knowledge_base_id,
        organization_id,
        extractor_version,
        chunker_version,
        embedding_model,
        chunk_count,
        state
    ) VALUES (
        NEW.document_version_id,
        NEW.index_generation,
        NEW.document_id,
        NEW.knowledge_base_id,
        NEW.organization_id,
        NEW.extractor_version,
        NEW.chunker_version,
        NEW.embedding_model,
        v_chunk_count,
        'ACTIVE'
    );

    /*
     * Publication point: the old searchable version remains visible until the
     * new generation has been completely validated and inserted above.
     */
    UPDATE public.knowledge_documents document
    SET searchable_version_id = NEW.document_version_id,
        version = version + 1
    WHERE document.id = NEW.document_id
      AND document.knowledge_base_id = NEW.knowledge_base_id
      AND document.organization_id = NEW.organization_id
      AND document.searchable_version_id IS DISTINCT FROM NEW.document_version_id;

    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------
-- 4. Generation lifecycle hardening.
-- ---------------------------------------------------------------------------

ALTER TABLE public.knowledge_index_generations
    ADD CONSTRAINT chk_knowledge_generation_chronology_v63
    CHECK (
        (retired_at IS NULL OR retired_at >= published_at)
        AND (collected_at IS NULL OR (retired_at IS NOT NULL AND collected_at >= retired_at))
    ) NOT VALID;

ALTER TABLE public.knowledge_index_generations
    VALIDATE CONSTRAINT chk_knowledge_generation_chronology_v63;

CREATE OR REPLACE FUNCTION public.guard_knowledge_generation_v57()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state IS DISTINCT FROM 'ACTIVE'
           OR NEW.retired_at IS NOT NULL
           OR NEW.collected_at IS NOT NULL THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'V63: new Knowledge generation must start ACTIVE';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: retain Knowledge generation tombstones';
    END IF;

    IF (to_jsonb(NEW) - ARRAY['state', 'retired_at', 'collected_at'])
       IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['state', 'retired_at', 'collected_at']) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: immutable Knowledge generation metadata';
    END IF;

    IF NOT (
        (OLD.state = 'ACTIVE'
            AND NEW.state = 'RETIRED'
            AND OLD.retired_at IS NULL
            AND NEW.retired_at IS NOT NULL
            AND NEW.retired_at >= OLD.published_at)
        OR
        (OLD.state = 'RETIRED'
            AND NEW.state = 'COLLECTING'
            AND NEW.retired_at = OLD.retired_at)
        OR
        (OLD.state = 'COLLECTING'
            AND NEW.state = 'COLLECTED'
            AND NEW.retired_at = OLD.retired_at
            AND NEW.collected_at IS NOT NULL
            AND NEW.collected_at >= NEW.retired_at)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: invalid Knowledge generation transition';
    END IF;

    /*
     * Protect the actually searchable generation. During a fenced reindex the
     * ingestion job pointer has already advanced to the NEW generation, so the
     * old generation may be retired by publish_knowledge_generation_v57().
     */
    IF EXISTS (
        SELECT 1
        FROM public.knowledge_ingestion_jobs job
        JOIN public.knowledge_documents document
          ON document.id = job.document_id
         AND document.knowledge_base_id = job.knowledge_base_id
         AND document.organization_id = job.organization_id
         AND document.searchable_version_id = job.document_version_id
        WHERE job.document_version_id = OLD.document_version_id
          AND job.index_generation = OLD.index_generation
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: searchable Knowledge generation is protected';
    END IF;

    IF NEW.state = 'COLLECTING'
       AND EXISTS (
            SELECT 1
            FROM public.knowledge_document_chunks chunk
            JOIN public.knowledge_retrieval_hits hit
              ON hit.chunk_id = chunk.id
            WHERE chunk.document_version_id = OLD.document_version_id
              AND chunk.index_generation = OLD.index_generation
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: retrieval evidence is protected';
    END IF;

    IF NEW.state = 'COLLECTED'
       AND EXISTS (
            SELECT 1
            FROM public.knowledge_document_chunks chunk
            WHERE chunk.document_version_id = OLD.document_version_id
              AND chunk.index_generation = OLD.index_generation
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: collected generation still has chunks';
    END IF;

    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_generation_guard_v57
    ON public.knowledge_index_generations;

CREATE TRIGGER trg_generation_guard_v57
BEFORE INSERT OR UPDATE OR DELETE
ON public.knowledge_index_generations
FOR EACH ROW
EXECUTE FUNCTION public.guard_knowledge_generation_v57();

-- ---------------------------------------------------------------------------
-- 5. Upload reconciliation: "needs review" is not the same as "reviewed".
-- ---------------------------------------------------------------------------

ALTER TABLE public.knowledge_storage_upload_intents
    ADD COLUMN needs_review_at timestamptz;

/* Existing Java used reviewed_at as queue-entry time; preserve that fact honestly. */
-- noinspection SqlResolve
UPDATE public.knowledge_storage_upload_intents
SET needs_review_at = reviewed_at,
    reviewed_at = NULL
WHERE state = 'NEEDS_REVIEW'
  AND reviewed_at IS NOT NULL;

-- noinspection SqlResolve
ALTER TABLE public.knowledge_storage_upload_intents
    ADD CONSTRAINT chk_knowledge_upload_review_time_v63
    CHECK (
        (state = 'NEEDS_REVIEW' AND needs_review_at IS NOT NULL)
        OR (state <> 'NEEDS_REVIEW')
    ) NOT VALID;

ALTER TABLE public.knowledge_storage_upload_intents
    VALIDATE CONSTRAINT chk_knowledge_upload_review_time_v63;

-- IntelliJ resolves trigger pseudo-record fields against the currently attached DB schema.
-- needs_review_at is created above in this same Flyway migration, so suppress only
-- the IDE's stale-schema SqlResolve warning for this routine.
-- noinspection SqlResolve
CREATE OR REPLACE FUNCTION public.enforce_knowledge_upload_intent_identity_v56()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: Knowledge upload intents cannot be deleted';
    END IF;

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
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: immutable Knowledge upload intent identity changed';
    END IF;

    IF NEW.state IS DISTINCT FROM OLD.state
       AND NOT (
            (OLD.state = 'INTENT'
                AND NEW.state IN ('STORED', 'NEEDS_REVIEW'))
            OR
            (OLD.state = 'STORED'
                AND NEW.state IN ('LINKED', 'CLEANING'))
            OR
            (OLD.state = 'CLEANING'
                AND NEW.state IN ('CLEANED', 'NEEDS_REVIEW'))
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: invalid Knowledge upload intent state transition';
    END IF;

    IF OLD.stored_at IS NOT NULL
       AND NEW.stored_at IS DISTINCT FROM OLD.stored_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: Knowledge upload stored_at evidence is immutable';
    END IF;

    IF OLD.linked_at IS NOT NULL
       AND NEW.linked_at IS DISTINCT FROM OLD.linked_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: Knowledge upload linked_at evidence is immutable';
    END IF;

    IF OLD.cleaned_at IS NOT NULL
       AND NEW.cleaned_at IS DISTINCT FROM OLD.cleaned_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: Knowledge upload cleaned_at evidence is immutable';
    END IF;

    IF OLD.needs_review_at IS NOT NULL
       AND NEW.needs_review_at IS DISTINCT FROM OLD.needs_review_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: Knowledge upload needs_review_at evidence is immutable';
    END IF;

    IF OLD.reviewed_at IS NOT NULL
       AND NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: Knowledge upload reviewed_at evidence is immutable';
    END IF;

    IF NEW.state = 'NEEDS_REVIEW' AND NEW.needs_review_at IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: NEEDS_REVIEW requires needs_review_at';
    END IF;

    IF NEW.state IS DISTINCT FROM OLD.state
       AND NEW.state = 'NEEDS_REVIEW'
       AND NEW.needs_review_at IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: review queue transition must record needs_review_at';
    END IF;

    IF NEW.state IS NOT DISTINCT FROM OLD.state
       AND (
            NEW.stored_at IS DISTINCT FROM OLD.stored_at
            OR NEW.linked_at IS DISTINCT FROM OLD.linked_at
            OR NEW.cleaned_at IS DISTINCT FROM OLD.cleaned_at
            OR NEW.cleanup_token IS DISTINCT FROM OLD.cleanup_token
            OR NEW.cleanup_claimed_at IS DISTINCT FROM OLD.cleanup_claimed_at
            OR NEW.needs_review_at IS DISTINCT FROM OLD.needs_review_at
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V63: Knowledge upload lifecycle evidence changed without state transition';
    END IF;

    RETURN NEW;
END
$$;

-- noinspection SqlResolve
COMMENT ON COLUMN public.knowledge_storage_upload_intents.needs_review_at IS
'Time the operation entered NEEDS_REVIEW. This is not evidence that a human review occurred.';

COMMENT ON COLUMN public.knowledge_storage_upload_intents.reviewed_at IS
'Reserved for the actual completed review workflow; V63 no longer writes it automatically.';
