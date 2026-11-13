-- SafeAI-Desk/backend/src/main/resources/db/migration/V82__knowledge_base_erasure_foundation.sql
-- SafeAI Desk V82: non-destructive knowledge base erasure foundation.
-- No historical migration is modified. No base or document content is deleted by Flyway.
-- Retain immutable historical RAG/evaluation references independently of a live base.
CREATE TABLE public.knowledge_base_identities_v82 (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    CONSTRAINT uq_knowledge_base_identities_tenant_v82 UNIQUE (id, organization_id)
);
INSERT INTO public.knowledge_base_identities_v82(id, organization_id)
SELECT id, organization_id FROM public.knowledge_bases;

CREATE FUNCTION public.register_knowledge_base_identity_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO public.knowledge_base_identities_v82 (id, organization_id)
    VALUES (NEW.id, NEW.organization_id);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_register_knowledge_base_identity_v82
AFTER INSERT ON public.knowledge_bases FOR EACH ROW
EXECUTE FUNCTION public.register_knowledge_base_identity_v82();

CREATE FUNCTION public.protect_knowledge_base_identity_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V82: base identity is immutable';
END $$;
CREATE TRIGGER trg_protect_knowledge_base_identity_v82
BEFORE UPDATE OR DELETE ON public.knowledge_base_identities_v82
FOR EACH ROW EXECUTE FUNCTION public.protect_knowledge_base_identity_v82();

-- Replace only the three direct historical FK references. Each new FK is
-- validated against populated data BEFORE dropping its former RESTRICT FK.
ALTER TABLE public.chat_turns
    ADD CONSTRAINT fk_chat_turns_knowledge_base_identity_v82
    FOREIGN KEY (knowledge_base_id, organization_id)
    REFERENCES public.knowledge_base_identities_v82(id, organization_id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.chat_turns
    VALIDATE CONSTRAINT fk_chat_turns_knowledge_base_identity_v82;
ALTER TABLE public.chat_turns
    DROP CONSTRAINT fk_chat_turns_knowledge_base_tenant;

ALTER TABLE public.knowledge_retrieval_runs
    ADD CONSTRAINT fk_knowledge_retrieval_runs_base_identity_v82
    FOREIGN KEY (knowledge_base_id, organization_id)
    REFERENCES public.knowledge_base_identities_v82(id, organization_id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.knowledge_retrieval_runs
    VALIDATE CONSTRAINT fk_knowledge_retrieval_runs_base_identity_v82;
ALTER TABLE public.knowledge_retrieval_runs
    DROP CONSTRAINT fk_knowledge_retrieval_runs_kb_org;

ALTER TABLE public.knowledge_evaluation_runs
    ADD CONSTRAINT fk_knowledge_evaluation_runs_base_identity_v82
    FOREIGN KEY (knowledge_base_id, organization_id)
    REFERENCES public.knowledge_base_identities_v82(id, organization_id)
    ON DELETE RESTRICT NOT VALID;
ALTER TABLE public.knowledge_evaluation_runs
    VALIDATE CONSTRAINT fk_knowledge_evaluation_runs_base_identity_v82;
ALTER TABLE public.knowledge_evaluation_runs
    DROP CONSTRAINT fk_knowledge_evaluation_runs_kb;

ALTER TABLE public.knowledge_bases
    ADD COLUMN lifecycle_state varchar(16) NOT NULL DEFAULT 'AVAILABLE',
    ADD CONSTRAINT chk_knowledge_base_lifecycle_v82
    CHECK (lifecycle_state IN ('AVAILABLE', 'DELETING'));

CREATE TABLE public.knowledge_base_deletions_v82 (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    requested_by_user_id uuid NOT NULL,
    expected_version bigint NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    state varchar(32) NOT NULL DEFAULT 'DELETING',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    last_error_code varchar(64),
    CONSTRAINT fk_knowledge_base_deletion_identity_v82
        FOREIGN KEY (knowledge_base_id, organization_id)
        REFERENCES public.knowledge_base_identities_v82 (id, organization_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_knowledge_base_delete_tenant_base_v82 UNIQUE (organization_id, knowledge_base_id),
    CONSTRAINT uq_knowledge_base_delete_tenant_key_v82 UNIQUE (organization_id, idempotency_key),
    CONSTRAINT chk_knowledge_base_delete_expected_version_v82 CHECK (expected_version >= 0),
    CONSTRAINT chk_knowledge_base_delete_attempts_v82 CHECK (attempts >= 0),
    CONSTRAINT chk_knowledge_base_delete_state_v82 CHECK (state IN
        ('DELETING','NEEDS_REVIEW','BLOCKED_BY_RETENTION','DELETED')),
    CONSTRAINT chk_knowledge_base_delete_lease_v82
        CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT chk_knowledge_base_delete_completion_v82 CHECK
        ((state = 'DELETED' AND completed_at IS NOT NULL) OR
         (state <> 'DELETED' AND completed_at IS NULL))
);
CREATE INDEX idx_knowledge_base_delete_pending_v82
    ON public.knowledge_base_deletions_v82 (next_attempt_at, id)
    WHERE state = 'DELETING';

CREATE FUNCTION public.guard_knowledge_base_deletion_identity_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V82: erasure evidence cannot be deleted';
    END IF;
    IF (NEW.id, NEW.organization_id, NEW.knowledge_base_id, NEW.requested_by_user_id,
        NEW.expected_version, NEW.idempotency_key, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.organization_id, OLD.knowledge_base_id, OLD.requested_by_user_id,
        OLD.expected_version, OLD.idempotency_key, OLD.created_at)
       OR (OLD.state = 'DELETED' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V82: immutable erasure operation identity';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_base_deletion_identity_v82
BEFORE UPDATE OR DELETE ON public.knowledge_base_deletions_v82
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_base_deletion_identity_v82();

CREATE FUNCTION public.guard_knowledge_base_lifecycle_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.lifecycle_state <> 'AVAILABLE' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V82: base must start AVAILABLE';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.lifecycle_state = 'DELETING' THEN
            IF NEW.lifecycle_state <> 'DELETING' OR NEW.enabled
                OR NEW.name IS DISTINCT FROM OLD.name
                OR NEW.visibility IS DISTINCT FROM OLD.visibility
                OR NEW.description IS DISTINCT FROM OLD.description THEN
                RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V82: deleting base is immutable';
            END IF;
        ELSIF NEW.lifecycle_state = 'DELETING' THEN
            IF NEW.enabled OR NOT EXISTS (
                SELECT 1 FROM public.knowledge_base_deletions_v82 op
                WHERE op.organization_id = NEW.organization_id
                  AND op.knowledge_base_id = NEW.id AND op.state = 'DELETING'
            ) THEN
                RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V82: durable deletion request required';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_base_lifecycle_v82
BEFORE INSERT OR UPDATE ON public.knowledge_bases
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_base_lifecycle_v82();

-- Serialize new writes with the base transition. In-progress writes that won
-- the base SHARE lock before deletion may finish before the transition commits.
CREATE FUNCTION public.require_available_base_for_new_content_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_state text;
BEGIN
    SELECT b.lifecycle_state INTO v_state
    FROM public.knowledge_bases b
    WHERE b.id = NEW.knowledge_base_id AND b.organization_id = NEW.organization_id
    FOR SHARE;
    IF v_state IS DISTINCT FROM 'AVAILABLE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V82: base unavailable for new content';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_new_document_base_v82
BEFORE INSERT ON public.knowledge_documents FOR EACH ROW
EXECUTE FUNCTION public.require_available_base_for_new_content_v82();
CREATE TRIGGER trg_knowledge_new_version_base_v82
BEFORE INSERT ON public.knowledge_document_versions FOR EACH ROW
EXECUTE FUNCTION public.require_available_base_for_new_content_v82();
CREATE TRIGGER trg_knowledge_new_upload_intent_base_v82
BEFORE INSERT ON public.knowledge_storage_upload_intents FOR EACH ROW
EXECUTE FUNCTION public.require_available_base_for_new_content_v82();
CREATE TRIGGER trg_knowledge_new_retrieval_run_base_v82
BEFORE INSERT ON public.knowledge_retrieval_runs FOR EACH ROW
EXECUTE FUNCTION public.require_available_base_for_new_content_v82();
CREATE TRIGGER trg_knowledge_new_evaluation_run_base_v82
BEFORE INSERT ON public.knowledge_evaluation_runs FOR EACH ROW
EXECUTE FUNCTION public.require_available_base_for_new_content_v82();

-- A late mutable document update must not re-enable or republish a base being erased.
-- Allow only the existing V78 document-erasure path to modify documents.
CREATE FUNCTION public.guard_document_update_base_erasure_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_state text;
BEGIN
    SELECT b.lifecycle_state INTO v_state FROM public.knowledge_bases b
    WHERE b.id = NEW.knowledge_base_id AND b.organization_id = NEW.organization_id FOR SHARE;
    IF v_state = 'DELETING' AND
       (NEW.lifecycle_state <> 'DELETING' OR NEW.enabled
        OR NEW.name IS DISTINCT FROM OLD.name) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V82: document update forbidden during base erasure';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_document_update_base_v82
BEFORE UPDATE ON public.knowledge_documents FOR EACH ROW
EXECUTE FUNCTION public.guard_document_update_base_erasure_v82();

-- Only new chat/RAG references must be prevented: completed historical rows
-- can still be finalized without rewriting their immutable reference fields.
CREATE FUNCTION public.guard_new_chat_base_reference_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_state text;
BEGIN
    IF NEW.knowledge_base_id IS NULL THEN RETURN NEW; END IF;
    SELECT lifecycle_state INTO v_state FROM public.knowledge_bases
    WHERE id = NEW.knowledge_base_id AND organization_id = NEW.organization_id FOR SHARE;
    IF v_state IS DISTINCT FROM 'AVAILABLE' THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V82: new chat reference to deleting base forbidden';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_new_chat_base_reference_v82
BEFORE INSERT OR UPDATE OF knowledge_base_id ON public.chat_turns
FOR EACH ROW EXECUTE FUNCTION public.guard_new_chat_base_reference_v82();

-- Independently fence the final live-base row deletion. The JDBC executor
-- supplies transaction-local operation/token; a forged session SET alone is not enough.
CREATE FUNCTION public.is_knowledge_base_erasure_owner_v82(p_base_id uuid)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE v_operation text; v_token text;
BEGIN
    v_operation := current_setting('safeai.knowledge.base_erasure_operation', true);
    v_token := current_setting('safeai.knowledge.base_erasure_token', true);
    IF v_operation IS NULL OR v_token IS NULL
       OR v_operation !~ '^[0-9a-fA-F-]{36}$' OR v_token !~ '^[0-9a-fA-F-]{36}$' THEN
        RETURN false;
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM public.knowledge_base_deletions_v82 op
        WHERE op.id = v_operation::uuid AND op.lease_token = v_token::uuid
          AND op.lease_until > clock_timestamp() AND op.state = 'DELETING'
          AND op.knowledge_base_id = p_base_id
    );
END $$;
CREATE FUNCTION public.guard_knowledge_base_physical_delete_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT public.is_knowledge_base_erasure_owner_v82(OLD.id)
       OR OLD.lifecycle_state <> 'DELETING' OR OLD.enabled THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V82: unfenced physical base deletion';
    END IF;
    IF EXISTS (SELECT 1 FROM public.knowledge_documents WHERE knowledge_base_id = OLD.id)
       OR EXISTS (SELECT 1 FROM public.knowledge_document_versions WHERE knowledge_base_id = OLD.id)
       OR EXISTS (SELECT 1 FROM public.knowledge_ingestion_jobs WHERE knowledge_base_id = OLD.id)
       OR EXISTS (SELECT 1 FROM public.knowledge_document_chunks WHERE knowledge_base_id = OLD.id)
       OR EXISTS (SELECT 1 FROM public.knowledge_document_deletions_v78
                  WHERE knowledge_base_id = OLD.id AND state <> 'DELETED')
       OR EXISTS (SELECT 1 FROM public.knowledge_storage_upload_intents
                  WHERE knowledge_base_id = OLD.id AND state NOT IN ('LINKED','CLEANED'))
       OR EXISTS (SELECT 1 FROM public.knowledge_document_erasure_objects_v81 e
                  JOIN public.knowledge_document_deletions_v78 op ON op.id = e.operation_id
                  WHERE op.knowledge_base_id = OLD.id) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V82: unfinished document cleanup';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER trg_knowledge_base_physical_delete_v82
BEFORE DELETE ON public.knowledge_bases FOR EACH ROW
EXECUTE FUNCTION public.guard_knowledge_base_physical_delete_v82();

INSERT INTO public.audit_event_types(name, description) VALUES
('KNOWLEDGE_BASE_DELETION_REQUESTED', 'Knowledge Base hard-delete request accepted with durable intent')
ON CONFLICT (name) DO NOTHING;

-- No new indexing generation or retrieval hit can be registered for a base
-- after the base's deletion transaction commits.
CREATE TRIGGER trg_knowledge_new_generation_base_v82
BEFORE INSERT ON public.knowledge_index_generations FOR EACH ROW
EXECUTE FUNCTION public.require_available_base_for_new_content_v82();
CREATE TRIGGER trg_knowledge_new_hit_base_v82
BEFORE INSERT ON public.knowledge_retrieval_hits FOR EACH ROW
EXECUTE FUNCTION public.require_available_base_for_new_content_v82();

-- The base owner/member ACL is not mutable during erasure. The one exception
-- is the already-fenced ON DELETE CASCADE when removing the fully-cleaned base.
CREATE FUNCTION public.guard_knowledge_base_membership_erasure_v82()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_base_id uuid; v_org_id uuid; v_state text;
BEGIN
    IF TG_OP='DELETE' THEN
        v_base_id := OLD.knowledge_base_id;
        v_org_id := OLD.organization_id;
        IF public.is_knowledge_base_erasure_owner_v82(v_base_id) THEN
            RETURN OLD;
        END IF;
    ELSE
        v_base_id := NEW.knowledge_base_id;
        v_org_id := NEW.organization_id;
    END IF;
    SELECT lifecycle_state INTO v_state FROM public.knowledge_bases
    WHERE id=v_base_id AND organization_id=v_org_id FOR SHARE;
    IF v_state IS DISTINCT FROM 'AVAILABLE' THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V82: membership cannot be changed while base is deleting';
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER trg_knowledge_base_membership_erasure_v82
BEFORE INSERT OR UPDATE OR DELETE ON public.knowledge_base_memberships
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_base_membership_erasure_v82();
