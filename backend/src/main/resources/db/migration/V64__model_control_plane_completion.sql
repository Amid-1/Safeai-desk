-- SafeAI Desk V64 — Model Control Plane completion hardening.
-- Additive only. V1-V63 remain immutable.
--
-- This migration closes the remaining V62-era gaps without changing historical
-- V1/V2/V3 route-decision hashes:
--   1) durable actual/reserved/unknown budget evidence + explicit cost state;
--   2) durable Knowledge RAG context receipt bound to the exact prepared request;
--   3) V62 prepared-request receipt upgraded to bind exact retrieval provenance;
--   4) application-visible schema invariants can be verified fail-fast at startup.

DO $$
BEGIN
    IF to_regclass('public.model_route_decisions') IS NULL
       OR to_regclass('public.model_route_prepared_requests_v62') IS NULL
       OR to_regclass('public.model_route_reserved_requests') IS NULL
       OR to_regclass('public.model_cost_commitment_ledger_v62') IS NULL
       OR to_regclass('public.knowledge_retrieval_runs') IS NULL
       OR to_regclass('public.chat_turns') IS NULL THEN
        RAISE EXCEPTION
            'V64 requires V62 Model Control Plane and V42+ Knowledge RAG schema';
    END IF;

    IF to_regprocedure('public.reject_model_control_plane_mutation_v45()') IS NULL THEN
        RAISE EXCEPTION
            'V64 requires reject_model_control_plane_mutation_v45()';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_trigger t
        JOIN pg_constraint c ON c.oid = t.tgconstraint
        WHERE t.tgrelid = 'public.model_route_decisions'::regclass
          AND t.tgname = 'ctrg_model_route_decision_turn_v45'
          AND c.condeferrable
    ) THEN
        RAISE EXCEPTION
            'V64 requires deferrable ctrg_model_route_decision_turn_v45';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. Immutable route-time budget evidence.
--
-- model_route_decisions.monthly_spent_usd is retained unchanged because it is
-- part of historical V1/V2/V3 canonical hashes. V64 gives that legacy field a
-- precise read model: actual provider-evidenced cost + active reservation, plus
-- unknown liability count and an explicit state.
-- ---------------------------------------------------------------------------
CREATE TABLE public.model_route_budget_commitments_v64 (
    model_route_decision_id uuid PRIMARY KEY
        REFERENCES public.model_route_decisions(id) ON DELETE RESTRICT,
    actual_cost_usd numeric(30,12),
    reserved_cost_usd numeric(30,12),
    unknown_cost_count bigint NOT NULL,
    cost_state varchar(48) NOT NULL,
    created_at timestamptz NOT NULL,

    CONSTRAINT chk_model_route_budget_commitments_values_v64
        CHECK (
            (actual_cost_usd IS NULL) = (reserved_cost_usd IS NULL)
            AND (actual_cost_usd IS NULL OR actual_cost_usd >= 0)
            AND (reserved_cost_usd IS NULL OR reserved_cost_usd >= 0)
            AND unknown_cost_count >= 0
        ),

    CONSTRAINT chk_model_route_budget_commitments_state_v64
        CHECK (cost_state IN (
            'KNOWN_WITHIN',
            'KNOWN_EXCEEDED',
            'UNKNOWN_WITHIN_LOWER_BOUND',
            'UNKNOWN_EXCEEDED_LOWER_BOUND',
            'UNVERIFIABLE'
        )),

    CONSTRAINT chk_model_route_budget_commitments_state_shape_v64
        CHECK (
            (cost_state IN ('KNOWN_WITHIN', 'KNOWN_EXCEEDED')
                AND actual_cost_usd IS NOT NULL
                AND unknown_cost_count = 0)
            OR
            (cost_state IN (
                    'UNKNOWN_WITHIN_LOWER_BOUND',
                    'UNKNOWN_EXCEEDED_LOWER_BOUND'
                )
                AND actual_cost_usd IS NOT NULL
                AND unknown_cost_count > 0)
            OR
            cost_state = 'UNVERIFIABLE'
        )
);

CREATE FUNCTION public.validate_model_route_budget_commitment_v64()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    d public.model_route_decisions%ROWTYPE;
    v_committed numeric;
BEGIN
    SELECT * INTO d
    FROM public.model_route_decisions
    WHERE id = NEW.model_route_decision_id;

    IF NOT FOUND
       OR d.monthly_budget_usd IS NULL
       OR NEW.created_at IS DISTINCT FROM d.created_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V64 budget evidence does not match an evaluated route decision';
    END IF;

    IF NEW.actual_cost_usd IS NOT NULL THEN
        v_committed := NEW.actual_cost_usd + NEW.reserved_cost_usd;

        IF d.monthly_spent_usd IS NOT NULL
           AND v_committed IS DISTINCT FROM d.monthly_spent_usd THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'V64 actual/reserved commitment does not match route decision';
        END IF;

        IF d.monthly_spent_usd IS NULL
           AND NEW.cost_state <> 'UNVERIFIABLE' THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'V64 representable commitment cannot disappear from route decision';
        END IF;
    ELSIF NEW.cost_state <> 'UNVERIFIABLE'
          OR d.monthly_spent_usd IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V64 missing commitment components are allowed only for unverifiable state';
    END IF;

    IF NEW.cost_state = 'KNOWN_WITHIN' AND (
            NOT d.monthly_cost_known
            OR d.budget_exceeded
            OR d.monthly_projected_usd IS NULL
            OR NEW.unknown_cost_count <> 0
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V64 KNOWN_WITHIN state contradicts route decision';
    END IF;

    IF NEW.cost_state = 'KNOWN_EXCEEDED' AND (
            NOT d.monthly_cost_known
            OR NOT d.budget_exceeded
            OR d.monthly_projected_usd IS NULL
            OR NEW.unknown_cost_count <> 0
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V64 KNOWN_EXCEEDED state contradicts route decision';
    END IF;

    IF NEW.cost_state = 'UNKNOWN_WITHIN_LOWER_BOUND' AND (
            d.monthly_cost_known
            OR d.budget_exceeded
            OR d.monthly_projected_usd IS NOT NULL
            OR NEW.unknown_cost_count = 0
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V64 UNKNOWN_WITHIN_LOWER_BOUND state contradicts route decision';
    END IF;

    IF NEW.cost_state = 'UNKNOWN_EXCEEDED_LOWER_BOUND' AND (
            d.monthly_cost_known
            OR NOT d.budget_exceeded
            OR d.monthly_projected_usd IS NOT NULL
            OR NEW.unknown_cost_count = 0
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V64 UNKNOWN_EXCEEDED_LOWER_BOUND state contradicts route decision';
    END IF;

    IF NEW.cost_state = 'UNVERIFIABLE' AND (
            d.monthly_cost_known
            OR d.budget_exceeded
            OR d.monthly_projected_usd IS NOT NULL
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V64 UNVERIFIABLE state contradicts route decision';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_model_route_budget_commitments_validate_v64
BEFORE INSERT ON public.model_route_budget_commitments_v64
FOR EACH ROW
EXECUTE FUNCTION public.validate_model_route_budget_commitment_v64();

CREATE TRIGGER trg_model_route_budget_commitments_immutable_v64
BEFORE UPDATE OR DELETE ON public.model_route_budget_commitments_v64
FOR EACH ROW
EXECUTE FUNCTION public.reject_model_control_plane_mutation_v45();

COMMENT ON TABLE public.model_route_budget_commitments_v64 IS
'Immutable route-time budget evidence. actual_cost_usd and reserved_cost_usd are separate; unknown is never interpreted as zero; cost_state records known/unknown/lower-bound/unverifiable semantics.';

-- ---------------------------------------------------------------------------
-- 2. Knowledge RAG context receipt.
--
-- This is written by KnowledgeRagService after retrieval/context assembly and
-- before execution identity issuance. It binds the exact RetrievalRun and
-- context hash to the exact fully prepared AiChatRequest hash.
-- ---------------------------------------------------------------------------
CREATE TABLE public.knowledge_rag_context_receipts_v64 (
    retrieval_run_id uuid PRIMARY KEY,
    chat_turn_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    embedding_model varchar(128) NOT NULL,
    context_sha256 varchar(64) NOT NULL,
    prepared_request_sha256 varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,

    CONSTRAINT fk_knowledge_rag_context_receipt_run_v64
        FOREIGN KEY (
            retrieval_run_id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        REFERENCES public.knowledge_retrieval_runs (
            id,
            knowledge_base_id,
            organization_id,
            user_id
        )
        ON DELETE RESTRICT,

    CONSTRAINT chk_knowledge_rag_context_receipt_text_v64
        CHECK (
            length(btrim(embedding_model)) BETWEEN 1 AND 128
            AND embedding_model = btrim(embedding_model)
            AND embedding_model !~ '[[:cntrl:]]'
        ),

    CONSTRAINT chk_knowledge_rag_context_receipt_hashes_v64
        CHECK (
            context_sha256 ~ '^[0-9a-f]{64}$'
            AND prepared_request_sha256 ~ '^[0-9a-f]{64}$'
        )
);

CREATE FUNCTION public.validate_knowledge_rag_context_receipt_v64()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    r record;
    t record;
BEGIN
    SELECT retrieval.chat_turn_id,
           retrieval.embedding_model
    INTO r
    FROM public.knowledge_retrieval_runs retrieval
    WHERE retrieval.id = NEW.retrieval_run_id
      AND retrieval.knowledge_base_id = NEW.knowledge_base_id
      AND retrieval.organization_id = NEW.organization_id
      AND retrieval.user_id = NEW.user_id;

    IF NOT FOUND
       OR r.chat_turn_id IS DISTINCT FROM NEW.chat_turn_id
       OR r.embedding_model IS DISTINCT FROM NEW.embedding_model THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V64 RAG context receipt does not match exact retrieval run';
    END IF;

    SELECT turn_row.organization_id,
           turn_row.user_id,
           turn_row.knowledge_base_id,
           turn_row.knowledge_mode
    INTO t
    FROM public.chat_turns turn_row
    WHERE turn_row.id = NEW.chat_turn_id;

    IF NOT FOUND
       OR t.organization_id IS DISTINCT FROM NEW.organization_id
       OR t.user_id IS DISTINCT FROM NEW.user_id
       OR t.knowledge_base_id IS DISTINCT FROM NEW.knowledge_base_id
       OR t.knowledge_mode NOT IN ('KNOWLEDGE_ASSISTED', 'KNOWLEDGE_ONLY') THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V64 RAG context receipt does not match exact Knowledge ChatTurn';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_knowledge_rag_context_receipt_validate_v64
BEFORE INSERT ON public.knowledge_rag_context_receipts_v64
FOR EACH ROW
EXECUTE FUNCTION public.validate_knowledge_rag_context_receipt_v64();

CREATE FUNCTION public.reject_knowledge_rag_context_receipt_mutation_v64()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'V64 Knowledge RAG context receipts are immutable';
END
$$;

CREATE TRIGGER trg_knowledge_rag_context_receipt_immutable_v64
BEFORE UPDATE OR DELETE ON public.knowledge_rag_context_receipts_v64
FOR EACH ROW
EXECUTE FUNCTION public.reject_knowledge_rag_context_receipt_mutation_v64();

COMMENT ON TABLE public.knowledge_rag_context_receipts_v64 IS
'Immutable proof that one exact chat retrieval/context assembly produced one exact prepared AI request before provider execution.';

-- ---------------------------------------------------------------------------
-- 3. Prepared request provenance v2.
-- Existing V62 rows become binding_version=1. New V64 Java writes version 2.
-- ---------------------------------------------------------------------------
ALTER TABLE public.model_route_prepared_requests_v62
    ADD COLUMN knowledge_base_id uuid,
    ADD COLUMN retrieval_run_id uuid,
    ADD COLUMN rag_context_sha256 varchar(64),
    ADD COLUMN binding_version smallint NOT NULL DEFAULT 1;

ALTER TABLE public.model_route_prepared_requests_v62
    ADD CONSTRAINT chk_model_route_prepared_binding_version_v64
        CHECK (binding_version IN (1, 2)) NOT VALID,
    ADD CONSTRAINT chk_model_route_prepared_knowledge_shape_v64
        CHECK (
            (knowledge_base_id IS NULL
             AND retrieval_run_id IS NULL
             AND rag_context_sha256 IS NULL)
            OR
            (knowledge_base_id IS NOT NULL
             AND retrieval_run_id IS NOT NULL
             AND rag_context_sha256 ~ '^[0-9a-f]{64}$')
        ) NOT VALID;

ALTER TABLE public.model_route_prepared_requests_v62
    VALIDATE CONSTRAINT chk_model_route_prepared_binding_version_v64;
ALTER TABLE public.model_route_prepared_requests_v62
    VALIDATE CONSTRAINT chk_model_route_prepared_knowledge_shape_v64;

CREATE INDEX idx_model_route_prepared_retrieval_v64
    ON public.model_route_prepared_requests_v62(retrieval_run_id)
    WHERE retrieval_run_id IS NOT NULL;

DROP TRIGGER IF EXISTS trg_model_route_prepared_requests_guard_v62
    ON public.model_route_prepared_requests_v62;

CREATE FUNCTION public.guard_model_route_prepared_request_v64()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    d public.model_route_decisions%ROWTYPE;
    t record;
BEGIN
    SELECT * INTO d
    FROM public.model_route_decisions
    WHERE id = NEW.model_route_decision_id;

    IF NOT FOUND
       OR d.outcome IS DISTINCT FROM 'ALLOWED'
       OR d.decision_integrity_version IS DISTINCT FROM 3
       OR d.chat_turn_id IS DISTINCT FROM NEW.chat_turn_id
       OR d.input_accounting_version IS DISTINCT FROM NEW.input_accounting_version
       OR d.estimated_input_tokens IS NULL
       OR NEW.prepared_input_units > d.estimated_input_tokens THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V64 prepared request differs from governed route envelope';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.model_route_reserved_requests base
        WHERE base.model_route_decision_id = NEW.model_route_decision_id
          AND base.chat_turn_id = NEW.chat_turn_id
          AND base.provider_operation_id = NEW.provider_operation_id
          AND base.base_request_sha256 = NEW.base_request_sha256
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V64 prepared request has no exact sealed pre-RAG reservation';
    END IF;

    -- binding_version=1 remains readable for historical V62 rows and rolling
    -- deployment. V64-capable Java always inserts binding_version=2.
    IF NEW.binding_version = 2 THEN
        SELECT turn_row.organization_id,
               turn_row.user_id,
               turn_row.knowledge_base_id,
               turn_row.knowledge_mode
        INTO t
        FROM public.chat_turns turn_row
        WHERE turn_row.id = NEW.chat_turn_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'V64 prepared request ChatTurn does not exist';
        END IF;

        IF t.knowledge_mode = 'GENERAL' THEN
            IF NEW.knowledge_base_id IS NOT NULL
               OR NEW.retrieval_run_id IS NOT NULL
               OR NEW.rag_context_sha256 IS NOT NULL THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'V64 GENERAL prepared request cannot carry RAG provenance';
            END IF;
        ELSIF t.knowledge_mode IN ('KNOWLEDGE_ASSISTED', 'KNOWLEDGE_ONLY') THEN
            IF NEW.knowledge_base_id IS NULL
               OR NEW.retrieval_run_id IS NULL
               OR NEW.rag_context_sha256 IS NULL
               OR NEW.knowledge_base_id IS DISTINCT FROM t.knowledge_base_id THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'V64 Knowledge prepared request lacks exact RAG provenance';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM public.knowledge_rag_context_receipts_v64 receipt
                WHERE receipt.retrieval_run_id = NEW.retrieval_run_id
                  AND receipt.chat_turn_id = NEW.chat_turn_id
                  AND receipt.organization_id = d.organization_id
                  AND receipt.user_id = d.user_id
                  AND receipt.knowledge_base_id = NEW.knowledge_base_id
                  AND receipt.context_sha256 = NEW.rag_context_sha256
                  AND receipt.prepared_request_sha256 = NEW.prepared_request_sha256
            ) THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'V64 prepared request is not the exact request sealed by Knowledge RAG';
            END IF;
        ELSE
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'V64 unsupported ChatTurn knowledge_mode';
        END IF;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_model_route_prepared_requests_guard_v64
BEFORE INSERT ON public.model_route_prepared_requests_v62
FOR EACH ROW
EXECUTE FUNCTION public.guard_model_route_prepared_request_v64();

COMMENT ON COLUMN public.model_route_prepared_requests_v62.binding_version IS
'1 = historical V62 binding; 2 = V64 binding with exact GENERAL/Knowledge provenance semantics.';
COMMENT ON COLUMN public.model_route_prepared_requests_v62.retrieval_run_id IS
'Exact Knowledge retrieval run that produced the sealed prepared request; null for GENERAL.';
COMMENT ON COLUMN public.model_route_prepared_requests_v62.rag_context_sha256 IS
'Exact Knowledge context SHA-256 from knowledge_rag_context_receipts_v64; null for GENERAL.';
