-- SafeAI-Desk/backend/src/main/resources/db/migration/V81__knowledge_erasure_executor_guards.sql
-- SafeAI Desk V81: opt-in, DB-fenced document-content erasure.
-- The executor and DELETE endpoint remain disabled by default in application config.
-- Preserve all historical Flyway files; preserve V80 provenance and V57 generation history.

CREATE TABLE public.knowledge_document_erasure_objects_v81 (
    operation_id uuid NOT NULL REFERENCES public.knowledge_document_deletions_v78(id) ON DELETE RESTRICT,
    storage_key varchar(1024) NOT NULL,
    PRIMARY KEY (operation_id, storage_key)
);
COMMENT ON TABLE public.knowledge_document_erasure_objects_v81 IS
    'Short-lived, tenant-protected durable original-object inventory. Delete when operation is verified DELETED.';

-- Never permit an uploader to put an untracked new object after erasure acceptance.
-- Previously started upload intents are separately checked by the executor.
CREATE FUNCTION public.guard_knowledge_upload_intent_for_erasure_v81()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_state text;
BEGIN
    SELECT d.lifecycle_state INTO v_state
    FROM public.knowledge_documents d
    WHERE d.id=NEW.document_id
      AND d.knowledge_base_id=NEW.knowledge_base_id
      AND d.organization_id=NEW.organization_id
    FOR SHARE;
    -- An initial upload intent precedes insertion of its new document row.
    -- Do not allow an old/deleting document ID to be recreated or reused.
    IF (v_state IS NULL AND EXISTS (
          SELECT 1 FROM public.knowledge_document_deletions_v78 op
          WHERE op.document_id=NEW.document_id AND op.organization_id=NEW.organization_id
        )) OR (v_state IS NOT NULL AND v_state <> 'AVAILABLE') THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V81: upload intent requires an available document';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_upload_intent_erasure_v81
BEFORE INSERT ON public.knowledge_storage_upload_intents
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_upload_intent_for_erasure_v81();

-- Only the current live lease owner can authorize content removal. A plain
-- session-level SET is insufficient: this function checks DB-fenced token too.
CREATE FUNCTION public.is_knowledge_erasure_lease_owner_v81(p_document_id uuid)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE op_text text;
DECLARE token_text text;
BEGIN
    op_text := current_setting('safeai.knowledge.erasure_operation', true);
    token_text := current_setting('safeai.knowledge.erasure_token', true);
    IF op_text IS NULL OR token_text IS NULL
       OR op_text !~ '^[0-9a-fA-F-]{36}$'
       OR token_text !~ '^[0-9a-fA-F-]{36}$' THEN
       RETURN false;
    END IF;
    RETURN EXISTS (
        SELECT 1
        FROM public.knowledge_document_deletions_v78 op
        JOIN public.knowledge_documents d
          ON d.id=op.document_id AND d.knowledge_base_id=op.knowledge_base_id
         AND d.organization_id=op.organization_id
        WHERE op.id=op_text::uuid AND op.lease_token=token_text::uuid
          AND op.lease_until > clock_timestamp() AND op.state='DELETING'
          AND d.lifecycle_state='DELETING' AND NOT d.enabled
          AND d.id=p_document_id
    );
END $$;

-- V57 GC is still the default. The single, fenced erasure transaction may
-- remove cited chunks because V80 now holds the same FK provenance independently.
CREATE OR REPLACE FUNCTION public.guard_chunk_gc_v57()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_state text;
BEGIN
    IF public.is_knowledge_erasure_lease_owner_v81(OLD.document_id) THEN
        RETURN OLD;
    END IF;
    SELECT g.state INTO v_state
    FROM public.knowledge_index_generations g
    WHERE g.document_version_id=OLD.document_version_id
      AND g.index_generation=OLD.index_generation
    FOR UPDATE;
    IF v_state IS DISTINCT FROM 'COLLECTING' THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V81: chunks may only be deleted by GC or fenced erasure';
    END IF;
    RETURN OLD;
END $$;

-- Historical citation names are user-supplied content. Replace only this field
-- during a fenced erasure; keep the immutable citation ID, rank, score, FK.
CREATE FUNCTION public.guard_knowledge_hit_erasure_v81()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_document_id uuid;
BEGIN
    SELECT ci.document_id INTO v_document_id
    FROM public.knowledge_chunk_identities_v80 ci WHERE ci.id=OLD.chunk_id;
    IF v_document_id IS NOT NULL
       AND public.is_knowledge_erasure_lease_owner_v81(v_document_id)
       AND NEW.document_name_snapshot='Удалённый документ'
       AND (to_jsonb(NEW) - 'document_name_snapshot')
           IS NOT DISTINCT FROM (to_jsonb(OLD) - 'document_name_snapshot') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE='23514',
        MESSAGE='V81: retrieval-hit provenance is immutable';
END $$;
DROP TRIGGER trg_knowledge_retrieval_hits_immutable_v44 ON public.knowledge_retrieval_hits;
CREATE TRIGGER trg_knowledge_retrieval_hits_immutable_v44
BEFORE UPDATE ON public.knowledge_retrieval_hits
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_hit_erasure_v81();

-- Terminal acceptance is impossible while any online content row or temporary
-- purge inventory survives. A DB constraint cannot independently prove S3
-- absence; the verified provider operation remains mandatory.
CREATE FUNCTION public.guard_knowledge_delete_completion_v81()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state='BLOCKED_BY_RETENTION' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V81: blocked-by-retention evidence is immutable';
    END IF;
    IF OLD.state <> 'DELETED' AND NEW.state='DELETED' THEN
        IF EXISTS (SELECT 1 FROM public.knowledge_documents WHERE id=NEW.document_id)
           OR EXISTS (SELECT 1 FROM public.knowledge_document_versions WHERE document_id=NEW.document_id)
           OR EXISTS (SELECT 1 FROM public.knowledge_ingestion_jobs WHERE document_id=NEW.document_id)
           OR EXISTS (SELECT 1 FROM public.knowledge_document_chunks WHERE document_id=NEW.document_id)
           OR EXISTS (SELECT 1 FROM public.knowledge_document_erasure_objects_v81 WHERE operation_id=NEW.id) THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='V81: online Knowledge content or purge inventory still exists';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_delete_completion_v81
BEFORE UPDATE ON public.knowledge_document_deletions_v78
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_delete_completion_v81();
