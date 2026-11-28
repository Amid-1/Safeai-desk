-- SafeAI-Desk/backend/src/main/resources/db/migration/V85__knowledge_rag_execution_leases_and_erasure_guards.sql
-- SafeAI Desk V85: per-physical-attempt Knowledge authorization leases.
-- Append-only: V1-V84 are not modified. The tables contain identifiers/hashes only,
-- never document text, embeddings, prompts, provider responses or storage credentials.

CREATE TABLE public.knowledge_rag_execution_permits_v85 (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    chat_turn_id uuid NOT NULL,
    retrieval_run_id uuid NOT NULL,
    context_sha256 char(64) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT fk_knowledge_rag_permit_base_v85
        FOREIGN KEY (knowledge_base_id, organization_id)
        REFERENCES public.knowledge_base_identities_v82(id, organization_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_knowledge_rag_permit_state_v85
        CHECK (state IN ('ACTIVE','COMPLETED','CLOSED','REVOKED','EXPIRED')),
    CONSTRAINT chk_knowledge_rag_permit_hash_v85
        CHECK (context_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_knowledge_rag_permit_expiry_v85
        CHECK (expires_at > created_at),
    CONSTRAINT chk_knowledge_rag_permit_completion_v85
        CHECK ((state IN ('COMPLETED','CLOSED','REVOKED','EXPIRED')) = (completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_knowledge_rag_permit_turn_v85
    ON public.knowledge_rag_execution_permits_v85(chat_turn_id);
CREATE INDEX idx_knowledge_rag_permit_base_active_v85
    ON public.knowledge_rag_execution_permits_v85(knowledge_base_id, state, expires_at);

-- Deliberately no FK to live documents/versions: these rows are minimal,
-- retained provenance and must survive physical erasure of the source rows.
CREATE TABLE public.knowledge_rag_execution_permit_sources_v85 (
    permit_id uuid NOT NULL REFERENCES public.knowledge_rag_execution_permits_v85(id) ON DELETE RESTRICT,
    organization_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    PRIMARY KEY (permit_id, document_id, document_version_id)
);
CREATE INDEX idx_knowledge_rag_permit_sources_document_v85
    ON public.knowledge_rag_execution_permit_sources_v85(organization_id, knowledge_base_id, document_id, permit_id);

CREATE TABLE public.knowledge_rag_execution_attempt_leases_v85 (
    id uuid PRIMARY KEY,
    permit_id uuid NOT NULL REFERENCES public.knowledge_rag_execution_permits_v85(id) ON DELETE RESTRICT,
    provider_attempt_id uuid NOT NULL UNIQUE,
    attempt_number integer NOT NULL,
    lease_token uuid NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    lease_until timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    heartbeat_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    error_code varchar(64),
    CONSTRAINT chk_knowledge_rag_attempt_number_v85 CHECK (attempt_number >= 1),
    CONSTRAINT chk_knowledge_rag_attempt_state_v85
        CHECK (state IN ('ACTIVE','SUCCEEDED','FAILED','AMBIGUOUS','ABORTED')),
    CONSTRAINT chk_knowledge_rag_attempt_completion_v85
        CHECK ((state = 'ACTIVE') = (completed_at IS NULL))
);
CREATE INDEX idx_knowledge_rag_attempt_active_v85
    ON public.knowledge_rag_execution_attempt_leases_v85(lease_until, permit_id)
    WHERE state = 'ACTIVE';

CREATE FUNCTION public.guard_knowledge_rag_permit_v85()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V85: Knowledge execution permit evidence is retained';
    END IF;
    IF (NEW.id, NEW.organization_id, NEW.knowledge_base_id, NEW.chat_turn_id,
        NEW.retrieval_run_id, NEW.context_sha256, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.organization_id, OLD.knowledge_base_id, OLD.chat_turn_id,
        OLD.retrieval_run_id, OLD.context_sha256, OLD.created_at) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V85: Knowledge execution permit identity is immutable';
    END IF;
    IF OLD.state <> 'ACTIVE' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V85: terminal Knowledge execution permit is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_rag_permit_v85
BEFORE UPDATE OR DELETE ON public.knowledge_rag_execution_permits_v85
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_rag_permit_v85();

CREATE FUNCTION public.guard_knowledge_rag_attempt_lease_v85()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V85: physical-attempt lease evidence is retained';
    END IF;
    IF (NEW.id, NEW.permit_id, NEW.provider_attempt_id, NEW.attempt_number,
        NEW.lease_token, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.permit_id, OLD.provider_attempt_id, OLD.attempt_number,
        OLD.lease_token, OLD.created_at) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V85: physical-attempt lease identity is immutable';
    END IF;
    IF OLD.state <> 'ACTIVE' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V85: terminal physical-attempt lease is immutable';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'ACTIVE'
       AND NEW.lease_until < OLD.lease_until THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V85: physical-attempt lease cannot be shortened';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_rag_attempt_lease_v85
BEFORE UPDATE OR DELETE ON public.knowledge_rag_execution_attempt_leases_v85
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_rag_attempt_lease_v85();

-- A parent retention block is immutable, matching the V81 child-operation rule.
-- Operator recovery must not silently clear a verified legal retention decision.
CREATE FUNCTION public.guard_knowledge_base_blocked_retention_v85()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'BLOCKED_BY_RETENTION' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V85: base BLOCKED_BY_RETENTION evidence is immutable';
    END IF;
    IF OLD.state <> 'DELETED' AND NEW.state = 'DELETED' THEN
        IF EXISTS (
            SELECT 1
              FROM public.knowledge_rag_execution_attempt_leases_v85 l
              JOIN public.knowledge_rag_execution_permits_v85 p ON p.id=l.permit_id
             WHERE p.knowledge_base_id=NEW.knowledge_base_id
               AND p.organization_id=NEW.organization_id
               AND l.state='ACTIVE'
        ) THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='V85: active Knowledge execution attempts prevent base deletion';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_base_blocked_retention_v85
BEFORE UPDATE ON public.knowledge_base_deletions_v82
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_base_blocked_retention_v85();

CREATE FUNCTION public.guard_knowledge_document_active_attempts_v85()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state <> 'DELETED' AND NEW.state = 'DELETED' THEN
        IF EXISTS (
            SELECT 1
              FROM public.knowledge_rag_execution_attempt_leases_v85 l
              JOIN public.knowledge_rag_execution_permits_v85 p ON p.id=l.permit_id
              JOIN public.knowledge_rag_execution_permit_sources_v85 s ON s.permit_id=p.id
             WHERE s.document_id=NEW.document_id
               AND s.knowledge_base_id=NEW.knowledge_base_id
               AND s.organization_id=NEW.organization_id
               AND l.state='ACTIVE'
        ) THEN
            RAISE EXCEPTION USING ERRCODE='23514',
                MESSAGE='V85: active Knowledge execution attempts prevent document deletion';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_knowledge_document_active_attempts_v85
BEFORE UPDATE ON public.knowledge_document_deletions_v78
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_document_active_attempts_v85();

COMMENT ON TABLE public.knowledge_rag_execution_permits_v85 IS
'Durable, content-free authorization receipt for one ChatTurn and exact Knowledge versions.';
COMMENT ON TABLE public.knowledge_rag_execution_attempt_leases_v85 IS
'One lease per physical provider attempt. Active rows fence erasure until terminal evidence or recovery.';
