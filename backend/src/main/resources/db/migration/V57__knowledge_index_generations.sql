/* Safeai-desk/backend/src/main/resources/db/migration/V57__knowledge_index_generations.sql */
/*
 * SafeAI Desk V57: durable Knowledge upload-intent hardening plus explicit
 * index-generation lifecycle/provenance.
 *
 * V1-V56 migration files are immutable and MUST NOT be edited because V55/V56
 * have already been applied. V57 therefore hardens the schema created by V56
 * additively and introduces generation-level provenance/GC invariants.
 */
DO $$
BEGIN
    IF to_regclass('public.knowledge_storage_upload_intents') IS NULL THEN
        RAISE EXCEPTION 'V57 requires V56 knowledge_storage_upload_intents';
    END IF;

    IF to_regclass('public.knowledge_document_versions') IS NULL
       OR to_regclass('public.knowledge_documents') IS NULL
       OR to_regclass('public.knowledge_document_chunks') IS NULL
       OR to_regclass('public.knowledge_ingestion_jobs') IS NULL
       OR to_regclass('public.knowledge_retrieval_hits') IS NULL THEN
        RAISE EXCEPTION 'V57 requires the existing Knowledge version/chunk/ingestion/retrieval schema';
    END IF;
END
$$;

/*
 * V56 is already published, so do not modify its migration file. Harden the
 * existing durable upload-intent table here instead.
 *
 * - journal rows cannot be deleted;
 * - immutable identity remains immutable;
 * - already-recorded lifecycle timestamps cannot be rewritten;
 * - cleanup fencing fields cannot change without a state transition;
 * - reviewed_at may be filled later by review workflow, but once present it
 *   is immutable evidence.
 */
CREATE OR REPLACE FUNCTION public.enforce_knowledge_upload_intent_identity_v56()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: Knowledge upload intents cannot be deleted';
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
            MESSAGE = 'V57: immutable Knowledge upload intent identity changed';
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
            MESSAGE = 'V57: invalid Knowledge upload intent state transition';
    END IF;

    /* Once durable timestamps exist, their values are evidence and immutable. */
    IF OLD.stored_at IS NOT NULL
       AND NEW.stored_at IS DISTINCT FROM OLD.stored_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: Knowledge upload stored_at evidence is immutable';
    END IF;

    IF OLD.linked_at IS NOT NULL
       AND NEW.linked_at IS DISTINCT FROM OLD.linked_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: Knowledge upload linked_at evidence is immutable';
    END IF;

    IF OLD.cleaned_at IS NOT NULL
       AND NEW.cleaned_at IS DISTINCT FROM OLD.cleaned_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: Knowledge upload cleaned_at evidence is immutable';
    END IF;

    IF OLD.reviewed_at IS NOT NULL
       AND NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: Knowledge upload reviewed_at evidence is immutable';
    END IF;

    /*
     * Lifecycle facts must not silently move while the row remains in the
     * same state. cleanup_token/cleanup_claimed_at may only be created/cleared
     * as part of the fenced STORED -> CLEANING -> terminal transition.
     */
    IF NEW.state IS NOT DISTINCT FROM OLD.state
       AND (
            NEW.stored_at IS DISTINCT FROM OLD.stored_at
            OR NEW.linked_at IS DISTINCT FROM OLD.linked_at
            OR NEW.cleaned_at IS DISTINCT FROM OLD.cleaned_at
            OR NEW.cleanup_token IS DISTINCT FROM OLD.cleanup_token
            OR NEW.cleanup_claimed_at IS DISTINCT FROM OLD.cleanup_claimed_at
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: Knowledge upload lifecycle evidence changed without state transition';
    END IF;

    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_knowledge_upload_intent_identity_v56
    ON public.knowledge_storage_upload_intents;

CREATE TRIGGER trg_knowledge_upload_intent_identity_v56
BEFORE UPDATE OR DELETE
ON public.knowledge_storage_upload_intents
FOR EACH ROW
EXECUTE FUNCTION public.enforce_knowledge_upload_intent_identity_v56();

/*
 * One row is the immutable provenance/tombstone for one physical index
 * generation of one document version.
 */
CREATE TABLE public.knowledge_index_generations (
    document_version_id uuid NOT NULL,
    index_generation uuid NOT NULL,
    document_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    extractor_version varchar(128) NOT NULL,
    chunker_version varchar(128) NOT NULL,
    embedding_model varchar(128) NOT NULL,
    chunk_count bigint NOT NULL,
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    publication_time_estimated boolean NOT NULL DEFAULT false,
    state varchar(16) NOT NULL,
    retired_at timestamptz,
    collected_at timestamptz,

    CONSTRAINT pk_knowledge_index_generations_v57
        PRIMARY KEY (document_version_id, index_generation),

    CONSTRAINT uq_knowledge_index_generation_identity_v57
        UNIQUE (
            document_version_id,
            index_generation,
            document_id,
            knowledge_base_id,
            organization_id
        ),

    CONSTRAINT fk_knowledge_index_generation_version_v57
        FOREIGN KEY (
            document_version_id,
            document_id,
            knowledge_base_id,
            organization_id
        )
        REFERENCES public.knowledge_document_versions(
            id,
            document_id,
            knowledge_base_id,
            organization_id
        )
        ON DELETE RESTRICT,

    CONSTRAINT chk_knowledge_index_generation_chunk_count_v57
        CHECK (chunk_count >= 0),

    CONSTRAINT chk_knowledge_index_generation_state_v57
        CHECK (state IN ('ACTIVE', 'RETIRED', 'COLLECTING', 'COLLECTED')),

    CONSTRAINT chk_knowledge_index_generation_lifecycle_v57
        CHECK (
            (state = 'ACTIVE'
                AND retired_at IS NULL
                AND collected_at IS NULL)
            OR
            (state IN ('RETIRED', 'COLLECTING')
                AND retired_at IS NOT NULL
                AND collected_at IS NULL)
            OR
            (state = 'COLLECTED'
                AND retired_at IS NOT NULL
                AND collected_at IS NOT NULL
                AND collected_at >= retired_at)
        )
);

/*
 * Historical backfill must never fabricate provenance from inconsistent chunk
 * rows. Abort with a diagnostic message before inserting generation facts.
 */
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.knowledge_document_chunks c
        WHERE c.extractor_version IS NULL
           OR c.chunker_version IS NULL
           OR c.embedding_model IS NULL
    ) THEN
        RAISE EXCEPTION 'V57: chunk exists without complete generation metadata';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.knowledge_document_chunks c
        GROUP BY c.document_version_id, c.index_generation
        HAVING count(DISTINCT (
                   c.document_id,
                   c.knowledge_base_id,
                   c.organization_id
               )) <> 1
            OR count(DISTINCT (
                   c.extractor_version,
                   c.chunker_version,
                   c.embedding_model
               )) <> 1
    ) THEN
        RAISE EXCEPTION 'V57: inconsistent generation identity or metadata';
    END IF;
END
$$;

/*
 * Backfill all generations represented by persisted chunks. The ingestion job
 * pointer identifies the current generation; older generations are retired.
 */
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
    published_at,
    publication_time_estimated,
    state,
    retired_at
)
SELECT
    c.document_version_id,
    c.index_generation,
    c.document_id,
    c.knowledge_base_id,
    c.organization_id,
    min(c.extractor_version),
    min(c.chunker_version),
    min(c.embedding_model),
    count(*),
    min(c.created_at),
    true,
    CASE
        WHEN j.index_generation = c.index_generation THEN 'ACTIVE'
        ELSE 'RETIRED'
    END,
    CASE
        WHEN j.index_generation = c.index_generation THEN NULL
        ELSE clock_timestamp()
    END
FROM public.knowledge_document_chunks c
JOIN public.knowledge_ingestion_jobs j
  ON j.document_version_id = c.document_version_id
GROUP BY
    c.document_version_id,
    c.index_generation,
    c.document_id,
    c.knowledge_base_id,
    c.organization_id,
    j.index_generation;

/*
 * READY job metadata is the authoritative publication record. If a generation
 * was already backfilled from chunks, both sources must agree exactly. If no
 * generation row exists, READY is allowed to describe only a genuinely empty
 * generation.
 */
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.knowledge_ingestion_jobs j
        WHERE j.status = 'READY'
          AND (
                j.index_generation IS NULL
                OR j.extractor_version IS NULL
                OR j.chunker_version IS NULL
                OR j.embedding_model IS NULL
                OR j.chunk_count IS NULL
          )
    ) THEN
        RAISE EXCEPTION 'V57: READY ingestion job has incomplete generation metadata';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.knowledge_ingestion_jobs j
        JOIN public.knowledge_index_generations g
          ON g.document_version_id = j.document_version_id
         AND g.index_generation = j.index_generation
        WHERE j.status = 'READY'
          AND (
                g.document_id IS DISTINCT FROM j.document_id
                OR g.knowledge_base_id IS DISTINCT FROM j.knowledge_base_id
                OR g.organization_id IS DISTINCT FROM j.organization_id
                OR g.extractor_version IS DISTINCT FROM j.extractor_version
                OR g.chunker_version IS DISTINCT FROM j.chunker_version
                OR g.embedding_model IS DISTINCT FROM j.embedding_model
                OR g.chunk_count IS DISTINCT FROM j.chunk_count
          )
    ) THEN
        RAISE EXCEPTION 'V57: READY ingestion job disagrees with backfilled generation provenance';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.knowledge_ingestion_jobs j
        LEFT JOIN public.knowledge_index_generations g
          ON g.document_version_id = j.document_version_id
         AND g.index_generation = j.index_generation
        WHERE j.status = 'READY'
          AND g.document_version_id IS NULL
          AND j.chunk_count <> 0
    ) THEN
        RAISE EXCEPTION 'V57: READY ingestion job claims chunks but no matching chunks were backfilled';
    END IF;
END
$$;

/* Empty READY generations have valid metadata even when chunk_count = 0. */
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
    published_at,
    publication_time_estimated,
    state
)
SELECT
    j.document_version_id,
    j.index_generation,
    j.document_id,
    j.knowledge_base_id,
    j.organization_id,
    j.extractor_version,
    j.chunker_version,
    j.embedding_model,
    0,
    coalesce(j.finished_at, j.created_at),
    true,
    'ACTIVE'
FROM public.knowledge_ingestion_jobs j
WHERE j.status = 'READY'
  AND j.chunk_count = 0
ON CONFLICT (document_version_id, index_generation) DO NOTHING;

/*
 * Explicit preflights give deterministic diagnostics rather than letting a
 * later FK/index build fail with an opaque constraint error.
 */
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.knowledge_document_chunks c
        LEFT JOIN public.knowledge_index_generations g
          ON g.document_version_id = c.document_version_id
         AND g.index_generation = c.index_generation
         AND g.document_id = c.document_id
         AND g.knowledge_base_id = c.knowledge_base_id
         AND g.organization_id = c.organization_id
        WHERE g.document_version_id IS NULL
    ) THEN
        RAISE EXCEPTION 'V57: chunk exists without generation provenance';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.knowledge_index_generations g
        WHERE g.state = 'ACTIVE'
        GROUP BY g.document_version_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V57: multiple ACTIVE generations exist for one document version';
    END IF;
END
$$;

/* DB invariant: one document version may have at most one ACTIVE generation. */
CREATE UNIQUE INDEX uq_knowledge_index_generations_one_active_v57
    ON public.knowledge_index_generations(document_version_id)
    WHERE state = 'ACTIVE';

ALTER TABLE public.knowledge_document_chunks
    ADD CONSTRAINT fk_chunks_generation_v57
    FOREIGN KEY (
        document_version_id,
        index_generation,
        document_id,
        knowledge_base_id,
        organization_id
    )
    REFERENCES public.knowledge_index_generations(
        document_version_id,
        index_generation,
        document_id,
        knowledge_base_id,
        organization_id
    )
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_generation_gc_v57
    ON public.knowledge_index_generations(state, retired_at);

CREATE INDEX idx_generation_retention_v57
    ON public.knowledge_index_generations(
        document_version_id,
        published_at DESC
    )
    WHERE state <> 'COLLECTED';

/*
 * Publication executes in the existing fenced chunk/job/audit transaction.
 * Retire the old ACTIVE generation first, then insert the new ACTIVE row, so
 * the partial unique index remains valid at every statement boundary.
 */
CREATE FUNCTION public.publish_knowledge_generation_v57()
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
            MESSAGE = 'V57: stale generation publication';
    END IF;

    SELECT count(*)
    INTO v_chunk_count
    FROM public.knowledge_document_chunks c
    WHERE c.document_version_id = NEW.document_version_id
      AND c.index_generation = NEW.index_generation;

    IF v_chunk_count <> NEW.chunk_count
       OR EXISTS (
            SELECT 1
            FROM public.knowledge_document_chunks c
            WHERE c.document_version_id = NEW.document_version_id
              AND c.index_generation = NEW.index_generation
              AND (
                    c.document_id IS DISTINCT FROM NEW.document_id
                    OR c.knowledge_base_id IS DISTINCT FROM NEW.knowledge_base_id
                    OR c.organization_id IS DISTINCT FROM NEW.organization_id
                    OR c.extractor_version IS DISTINCT FROM NEW.extractor_version
                    OR c.chunker_version IS DISTINCT FROM NEW.chunker_version
                    OR c.embedding_model IS DISTINCT FROM NEW.embedding_model
              )
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: publication metadata mismatch';
    END IF;

    UPDATE public.knowledge_index_generations g
    SET state = 'RETIRED',
        retired_at = clock_timestamp()
    WHERE g.document_version_id = OLD.document_version_id
      AND g.index_generation = OLD.index_generation
      AND g.state = 'ACTIVE';

    GET DIAGNOSTICS v_retired_rows = ROW_COUNT;

    /*
     * Zero rows is valid for the first publication after legacy state where no
     * generation tombstone existed. More than one is impossible by PK.
     * If another ACTIVE generation exists, do not guess which one to retire.
     */
    IF v_retired_rows = 0
       AND EXISTS (
            SELECT 1
            FROM public.knowledge_index_generations g
            WHERE g.document_version_id = NEW.document_version_id
              AND g.state = 'ACTIVE'
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: ACTIVE generation does not match previous ingestion pointer';
    END IF;

    IF OLD.lease_until < clock_timestamp() THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: ingestion lease expired while publishing';
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
    )
    VALUES (
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

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_generation_publication_v57
AFTER UPDATE OF index_generation
ON public.knowledge_ingestion_jobs
FOR EACH ROW
EXECUTE FUNCTION public.publish_knowledge_generation_v57();

/* A completed index is a sealed set, including chunks not yet cited. */
CREATE FUNCTION public.seal_knowledge_generation_v57()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    /*
     * Serialize append checks with the publisher, including writers waiting at
     * READ COMMITTED. The job row is the per-document-version publication lock.
     */
    PERFORM 1
    FROM public.knowledge_ingestion_jobs j
    WHERE j.document_version_id = NEW.document_version_id
    FOR UPDATE;

    IF EXISTS (
        SELECT 1
        FROM public.knowledge_index_generations g
        WHERE g.document_version_id = NEW.document_version_id
          AND g.index_generation = NEW.index_generation
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: generation already sealed';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_generation_sealed_v57
BEFORE INSERT
ON public.knowledge_document_chunks
FOR EACH ROW
EXECUTE FUNCTION public.seal_knowledge_generation_v57();

/* Generation metadata is immutable; only the explicit GC state machine moves. */
CREATE FUNCTION public.guard_knowledge_generation_v57()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: retain generation tombstones';
    END IF;

    IF (to_jsonb(NEW) - ARRAY['state', 'retired_at', 'collected_at'])
       IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['state', 'retired_at', 'collected_at']) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: immutable generation metadata';
    END IF;

    IF NOT (
        (OLD.state = 'ACTIVE'
            AND NEW.state = 'RETIRED')
        OR
        (OLD.state = 'RETIRED'
            AND NEW.state = 'COLLECTING'
            AND NEW.retired_at = OLD.retired_at)
        OR
        (OLD.state = 'COLLECTING'
            AND NEW.state = 'COLLECTED'
            AND NEW.retired_at = OLD.retired_at)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: invalid generation transition';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.knowledge_ingestion_jobs j
        JOIN public.knowledge_documents d
          ON d.id = j.document_id
         AND d.current_version_id = j.document_version_id
        WHERE j.document_version_id = OLD.document_version_id
          AND j.index_generation = OLD.index_generation
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: current generation is protected';
    END IF;

    IF NEW.state = 'COLLECTING'
       AND EXISTS (
            SELECT 1
            FROM public.knowledge_document_chunks c
            JOIN public.knowledge_retrieval_hits h
              ON h.chunk_id = c.id
            WHERE c.document_version_id = OLD.document_version_id
              AND c.index_generation = OLD.index_generation
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: retrieval evidence is protected';
    END IF;

    IF NEW.state = 'COLLECTED'
       AND EXISTS (
            SELECT 1
            FROM public.knowledge_document_chunks c
            WHERE c.document_version_id = OLD.document_version_id
              AND c.index_generation = OLD.index_generation
       ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: generation still has chunks';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_generation_guard_v57
BEFORE UPDATE OR DELETE
ON public.knowledge_index_generations
FOR EACH ROW
EXECUTE FUNCTION public.guard_knowledge_generation_v57();

/* Row lock is the GC/retrieval fence; existing evidence FKs remain final guard. */
CREATE FUNCTION public.guard_chunk_gc_v57()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_state text;
BEGIN
    SELECT g.state
    INTO v_state
    FROM public.knowledge_index_generations g
    WHERE g.document_version_id = OLD.document_version_id
      AND g.index_generation = OLD.index_generation
    FOR UPDATE;

    IF v_state IS DISTINCT FROM 'COLLECTING' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: chunks may only be deleted by generation GC';
    END IF;

    RETURN OLD;
END
$$;

CREATE TRIGGER trg_chunk_gc_guard_v57
BEFORE DELETE
ON public.knowledge_document_chunks
FOR EACH ROW
EXECUTE FUNCTION public.guard_chunk_gc_v57();

/* COLLECTING is transaction-local. A crash rolls back the entire deletion. */
CREATE FUNCTION public.finish_generation_gc_v57()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.knowledge_index_generations g
        WHERE g.document_version_id = NEW.document_version_id
          AND g.index_generation = NEW.index_generation
          AND g.state = 'COLLECTING'
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: incomplete GC transaction';
    END IF;

    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ctrg_generation_gc_complete_v57
AFTER UPDATE
ON public.knowledge_index_generations
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION public.finish_generation_gc_v57();

/*
 * A document cannot resurrect a retired/collected generation by changing its
 * current version. A pending version with no published generation stays legal.
 */
CREATE FUNCTION public.guard_current_generation_v57()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_state text;
BEGIN
    IF NEW.current_version_id IS NOT DISTINCT FROM OLD.current_version_id THEN
        RETURN NEW;
    END IF;

    SELECT g.state
    INTO v_state
    FROM public.knowledge_index_generations g
    JOIN public.knowledge_ingestion_jobs j
      ON j.document_version_id = g.document_version_id
     AND j.index_generation = g.index_generation
    WHERE g.document_version_id = NEW.current_version_id
    FOR SHARE OF g;

    IF FOUND AND v_state <> 'ACTIVE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V57: cannot restore a retired generation';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_current_generation_guard_v57
BEFORE UPDATE OF current_version_id
ON public.knowledge_documents
FOR EACH ROW
EXECUTE FUNCTION public.guard_current_generation_v57();

COMMENT ON TABLE public.knowledge_index_generations IS
'Immutable provenance/tombstone for each Knowledge index generation. At most one ACTIVE generation is allowed per document version; retired generations may be collected only through fenced GC.';
