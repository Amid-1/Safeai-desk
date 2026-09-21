/* Safeai-desk/backend/src/main/resources/db/migration/V53__physical_attempt_transition_and_pricing_integrity.sql */
/*
 * V53 — physical attempt transition safety and price evidence NULL hardening.
 *
 * Requires V52 (strict v3 catalog identity) and V50/V51 (attempt lifecycle).
 * Existing V45–V52 migrations and immutable evidence are NEVER rewritten.
 * All preflight failures require investigation; do not force a migration by
 * deleting, updating or retroactively inventing provider evidence.
 */
DO $$
BEGIN
    IF to_regclass('public.model_catalog_entries') IS NULL
       OR to_regclass('public.model_route_decisions') IS NULL
       OR to_regclass('public.chat_turns') IS NULL
       OR to_regclass('public.model_execution_plans') IS NULL
       OR to_regclass('public.model_execution_attempts') IS NULL THEN
        RAISE EXCEPTION 'V53 requires V45–V52 governance and physical execution tables';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.model_route_decisions'::regclass
          AND conname = 'chk_model_route_decisions_strict_catalog_v52'
          AND convalidated
    ) THEN
        RAISE EXCEPTION 'V53 requires fully validated V52 strict catalog constraint';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.model_catalog_entries
        WHERE pricing_status = 'FREE'
          AND (pricing_complete IS DISTINCT FROM TRUE
               OR input_usd_per_1m_tokens IS DISTINCT FROM 0
               OR output_usd_per_1m_tokens IS DISTINCT FROM 0)
    ) THEN
        RAISE EXCEPTION 'V53 preflight: NULL or nonzero FREE catalog core prices';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.model_execution_attempts
        WHERE (
            CASE
                WHEN pricing_status IS NULL
                  OR pricing_status IN ('UNPRICED', 'CALCULATION_FAILED') THEN
                    cost_usd IS NULL AND currency IS NULL AND price_book_version IS NULL
                WHEN pricing_status IN ('FREE', 'PRICED') THEN
                    cost_usd IS NOT NULL
                    AND (pricing_status <> 'FREE' OR cost_usd = 0)
                    AND currency IS NOT NULL AND currency = 'USD'
                    AND price_book_version IS NOT NULL
                    AND price_book_version = btrim(price_book_version)
                    AND length(price_book_version) BETWEEN 1 AND 64
                    AND price_book_version !~ '[[:cntrl:]]'
                ELSE FALSE
            END
          ) IS DISTINCT FROM TRUE
    ) THEN
        RAISE EXCEPTION 'V53 preflight: invalid immutable provider pricing evidence';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM public.model_execution_plans p
        JOIN public.chat_turns t ON t.id = p.chat_turn_id
        LEFT JOIN public.model_route_decisions d ON d.id = p.model_route_decision_id
        WHERE p.provider_operation_id IS DISTINCT FROM t.provider_operation_id
           OR p.organization_id IS DISTINCT FROM t.organization_id
           OR p.model_route_decision_id IS DISTINCT FROM t.model_route_decision_id
           OR p.requested_model IS DISTINCT FROM t.requested_model
           OR d.id IS NULL
           OR d.outcome IS DISTINCT FROM 'ALLOWED'
           OR d.decision_integrity_version IS DISTINCT FROM 3
           OR p.requested_model IS DISTINCT FROM d.selected_provider_model_id
    ) THEN
        RAISE EXCEPTION 'V53 preflight: historical execution plan differs from ChatTurn/v3 route';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM public.model_execution_attempts a
        JOIN public.model_execution_plans p ON p.id = a.execution_plan_id
        JOIN public.model_route_decisions d ON d.id = p.model_route_decision_id
        WHERE a.provider_type IS DISTINCT FROM d.selected_provider
           OR a.requested_physical_model IS DISTINCT FROM d.selected_provider_model_id
           OR a.requested_physical_model IS DISTINCT FROM p.requested_model
    ) THEN
        RAISE EXCEPTION 'V53 preflight: historical physical attempt does not match route';
    END IF;
    IF EXISTS (
        WITH ordered AS (
            SELECT a.*,
                   row_number() OVER w AS seq,
                   lag(a.attempt_number) OVER w AS prev_number,
                   lag(a.outcome) OVER w AS prev_outcome,
                   lag(a.outcome_certainty) OVER w AS prev_certainty,
                   lag(a.retry_safety) OVER w AS prev_retry,
                   lag(a.provider_type) OVER w AS prev_provider,
                   lag(a.provider_configuration_ref) OVER w AS prev_config,
                   lag(a.provider_configuration_version) OVER w AS prev_config_version,
                   lag(a.deployment_ref) OVER w AS prev_deployment,
                   lag(a.deployment_version) OVER w AS prev_deployment_version,
                   lag(a.requested_physical_model) OVER w AS prev_model
            FROM public.model_execution_attempts a
            WINDOW w AS (PARTITION BY a.execution_plan_id ORDER BY a.attempt_number)
        )
        SELECT 1 FROM ordered a
        WHERE a.attempt_number <> a.seq
           OR (a.attempt_number > 1 AND (
               a.prev_number IS DISTINCT FROM a.attempt_number - 1
               OR a.prev_outcome IS DISTINCT FROM 'FAILED'
               OR a.prev_certainty NOT IN ('KNOWN_NOT_EXECUTED', 'KNOWN_REJECTED')
               OR a.prev_retry IS DISTINCT FROM 'SAME_TARGET_RETRY_ALLOWED'
               OR a.provider_type IS DISTINCT FROM a.prev_provider
               OR a.provider_configuration_ref IS DISTINCT FROM a.prev_config
               OR a.provider_configuration_version IS DISTINCT FROM a.prev_config_version
               OR a.deployment_ref IS DISTINCT FROM a.prev_deployment
               OR a.deployment_version IS DISTINCT FROM a.prev_deployment_version
               OR a.requested_physical_model IS DISTINCT FROM a.prev_model
           ))
    ) THEN
        RAISE EXCEPTION 'V53 preflight: historical attempts violate the no-blind-retry state machine';
    END IF;
END
$$;

-- PostgreSQL CHECK accepts UNKNOWN: V49 FREE core prices were vulnerable.
-- Keep V49 generic price semantics (including arbitrary cache rate ordering).
ALTER TABLE public.model_catalog_entries
    ADD CONSTRAINT chk_model_catalog_entries_free_core_prices_not_null_v53
    CHECK (
        pricing_status <> 'FREE'
        OR (pricing_complete IS TRUE
            AND input_usd_per_1m_tokens IS NOT NULL
            AND input_usd_per_1m_tokens = 0
            AND output_usd_per_1m_tokens IS NOT NULL
            AND output_usd_per_1m_tokens = 0)
    ) NOT VALID;
ALTER TABLE public.model_catalog_entries
    VALIDATE CONSTRAINT chk_model_catalog_entries_free_core_prices_not_null_v53;

-- All pricing-status branches evaluate to TRUE or FALSE, never SQL UNKNOWN.
ALTER TABLE public.model_execution_attempts
    ADD CONSTRAINT chk_model_execution_attempt_price_evidence_strict_v53
    CHECK ((
        CASE
            WHEN pricing_status IS NULL
              OR pricing_status IN ('UNPRICED', 'CALCULATION_FAILED') THEN
                cost_usd IS NULL AND currency IS NULL AND price_book_version IS NULL
            WHEN pricing_status IN ('FREE', 'PRICED') THEN
                cost_usd IS NOT NULL
                AND (pricing_status <> 'FREE' OR cost_usd = 0)
                AND currency IS NOT NULL AND currency = 'USD'
                AND price_book_version IS NOT NULL
                AND price_book_version = btrim(price_book_version)
                AND length(price_book_version) BETWEEN 1 AND 64
                AND price_book_version !~ '[[:cntrl:]]'
            ELSE FALSE
        END
    ) IS TRUE) NOT VALID;
ALTER TABLE public.model_execution_attempts
    VALIDATE CONSTRAINT chk_model_execution_attempt_price_evidence_strict_v53;

-- V3 hashes in V48 seal the accounting envelope but not the exact history
-- payload. Seal the pre-RAG base in the SAME DB transaction as reservation.
-- This companion cannot retroactively prove a pre-V53 payload; old decisions
-- remain readable, but old unsealed plans cannot initiate new provider calls.
CREATE TABLE public.model_route_reserved_requests (
    model_route_decision_id uuid PRIMARY KEY
        REFERENCES public.model_route_decisions(id) ON DELETE RESTRICT,
    chat_turn_id uuid NOT NULL UNIQUE
        REFERENCES public.chat_turns(id) ON DELETE RESTRICT,
    provider_operation_id uuid NOT NULL UNIQUE,
    base_request_sha256 varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT chk_model_route_reserved_base_hash_v53
        CHECK (base_request_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE FUNCTION public.enforce_model_route_reserved_request_v53()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_decision public.model_route_decisions%ROWTYPE;
    v_turn public.chat_turns%ROWTYPE;
BEGIN
    SELECT * INTO v_decision FROM public.model_route_decisions
     WHERE id = NEW.model_route_decision_id;
    SELECT * INTO v_turn FROM public.chat_turns
     WHERE id = NEW.chat_turn_id;
    IF v_decision.id IS NULL OR v_turn.id IS NULL
       OR v_decision.outcome IS DISTINCT FROM 'ALLOWED'
       OR v_decision.decision_integrity_version IS DISTINCT FROM 3
       OR v_decision.selected_catalog_entry_id IS NULL
       OR NEW.chat_turn_id IS DISTINCT FROM v_decision.chat_turn_id
       OR NEW.chat_turn_id IS DISTINCT FROM v_turn.id
       OR NEW.model_route_decision_id IS DISTINCT FROM v_turn.model_route_decision_id
       OR NEW.provider_operation_id IS DISTINCT FROM v_turn.provider_operation_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 sealed base must match an exact v3 ChatTurn/route/operation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_model_route_reserved_request_v53
BEFORE INSERT ON public.model_route_reserved_requests
FOR EACH ROW EXECUTE FUNCTION public.enforce_model_route_reserved_request_v53();
CREATE TRIGGER trg_model_route_reserved_request_immutable_v53
BEFORE UPDATE OR DELETE ON public.model_route_reserved_requests
FOR EACH ROW EXECUTE FUNCTION public.reject_model_control_plane_mutation_v45();

-- The legacy v50 plan trigger checks operation/org/route only. New plans
-- require the exact requested model AND executable v3 route evidence.
CREATE FUNCTION public.enforce_model_execution_plan_governance_v53()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_turn public.chat_turns%ROWTYPE;
    v_decision public.model_route_decisions%ROWTYPE;
BEGIN
    SELECT * INTO v_turn FROM public.chat_turns WHERE id = NEW.chat_turn_id;
    IF NOT FOUND OR
       NEW.provider_operation_id IS DISTINCT FROM v_turn.provider_operation_id OR
       NEW.organization_id IS DISTINCT FROM v_turn.organization_id OR
       NEW.model_route_decision_id IS DISTINCT FROM v_turn.model_route_decision_id OR
       NEW.requested_model IS DISTINCT FROM v_turn.requested_model THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 plan identity must match its exact ChatTurn';
    END IF;

    IF NEW.model_route_decision_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 new physical execution requires a governed v3 route';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.model_route_reserved_requests b
        WHERE b.model_route_decision_id = NEW.model_route_decision_id
          AND b.chat_turn_id = NEW.chat_turn_id
          AND b.provider_operation_id = NEW.provider_operation_id
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 provider plan has no immutable exact pre-RAG base seal';
    END IF;
    SELECT * INTO v_decision FROM public.model_route_decisions
     WHERE id = NEW.model_route_decision_id;
    IF NOT FOUND OR v_decision.outcome IS DISTINCT FROM 'ALLOWED'
       OR v_decision.decision_integrity_version IS DISTINCT FROM 3
       OR v_decision.selected_catalog_entry_id IS NULL
       OR NEW.requested_model IS DISTINCT FROM v_decision.selected_provider_model_id
       OR NEW.organization_id IS DISTINCT FROM v_decision.organization_id
       OR NEW.chat_turn_id IS DISTINCT FROM v_decision.chat_turn_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 plan model/turn does not match allowed catalog-backed route';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_model_execution_plan_governance_v53
BEFORE INSERT ON public.model_execution_plans
FOR EACH ROW EXECUTE FUNCTION public.enforce_model_execution_plan_governance_v53();

-- Final DB integrity boundary. V50 locks the plan to serialize attempt numbers;
-- this trigger takes the SAME lock and checks the predecessor after acquiring it.
-- No fallback is executable until immutable fallback authorization exists.
CREATE FUNCTION public.enforce_model_execution_attempt_transition_v53()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_plan public.model_execution_plans%ROWTYPE;
    v_decision public.model_route_decisions%ROWTYPE;
    v_previous public.model_execution_attempts%ROWTYPE;
    v_latest_number integer;
BEGIN
    SELECT * INTO v_plan FROM public.model_execution_plans
     WHERE id = NEW.execution_plan_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 physical attempt has no execution plan';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.model_route_reserved_requests b
        WHERE b.model_route_decision_id = v_plan.model_route_decision_id
          AND b.chat_turn_id = v_plan.chat_turn_id
          AND b.provider_operation_id = v_plan.provider_operation_id
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 unsealed historical execution plan cannot start a physical call';
    END IF;
    SELECT * INTO v_decision FROM public.model_route_decisions
     WHERE id = v_plan.model_route_decision_id;
    IF NOT FOUND OR v_decision.outcome IS DISTINCT FROM 'ALLOWED'
       OR v_decision.decision_integrity_version IS DISTINCT FROM 3
       OR v_decision.selected_catalog_entry_id IS NULL
       OR NEW.provider_type IS DISTINCT FROM v_decision.selected_provider
       OR NEW.requested_physical_model IS DISTINCT FROM v_decision.selected_provider_model_id
       OR NEW.requested_physical_model IS DISTINCT FROM v_plan.requested_model THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 physical provider/model differs from governed target';
    END IF;

    SELECT * INTO v_previous
    FROM public.model_execution_attempts
    WHERE execution_plan_id = NEW.execution_plan_id
    ORDER BY attempt_number DESC
    LIMIT 1;
    v_latest_number := COALESCE(v_previous.attempt_number, 0);
    IF NEW.attempt_number <> v_latest_number + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 physical attempts must remain contiguous';
    END IF;
    IF NEW.attempt_number = 1 THEN
        RETURN NEW;
    END IF;

    IF v_previous.outcome IS DISTINCT FROM 'FAILED'
       OR v_previous.outcome_certainty NOT IN ('KNOWN_NOT_EXECUTED', 'KNOWN_REJECTED')
       OR v_previous.retry_safety IS DISTINCT FROM 'SAME_TARGET_RETRY_ALLOWED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 previous physical attempt does not authorize retry';
    END IF;
    IF NEW.provider_type IS DISTINCT FROM v_previous.provider_type
       OR NEW.provider_configuration_ref IS DISTINCT FROM v_previous.provider_configuration_ref
       OR NEW.provider_configuration_version IS DISTINCT FROM v_previous.provider_configuration_version
       OR NEW.deployment_ref IS DISTINCT FROM v_previous.deployment_ref
       OR NEW.deployment_version IS DISTINCT FROM v_previous.deployment_version
       OR NEW.requested_physical_model IS DISTINCT FROM v_previous.requested_physical_model THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V53 same-target retry changed provider/config/deployment/model';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_model_execution_attempt_transition_v53
BEFORE INSERT ON public.model_execution_attempts
FOR EACH ROW EXECUTE FUNCTION public.enforce_model_execution_attempt_transition_v53();

COMMENT ON CONSTRAINT chk_model_catalog_entries_free_core_prices_not_null_v53
ON public.model_catalog_entries IS
'FREE catalog prices must be explicit zeros, never SQL UNKNOWN.';
COMMENT ON CONSTRAINT chk_model_execution_attempt_price_evidence_strict_v53
ON public.model_execution_attempts IS
'Physical pricing status branches have explicit non-NULL cost/currency/version evidence as applicable.';
COMMENT ON FUNCTION public.enforce_model_execution_attempt_transition_v53() IS
'Concurrent-safe physical call state machine: prior FAILED + retry allowed + identical target only; fallback is unavailable.';
