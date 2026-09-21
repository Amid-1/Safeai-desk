/* Safeai-desk/backend/src/main/resources/db/migration/V54__provider_identity_and_reconciliation_safety.sql */
/*
 * V54 — additive follow-up to already published V52/V53.
 * NEVER edit Flyway V45–V53 checksums or rewrite immutable attempt evidence.
 * V53 remains the terminal authorization source for attempts; V54 narrows the
 * accepted lifecycle and provides read-only financial reconciliation discovery.
 */
DO $$
BEGIN
    IF to_regclass('public.model_execution_attempts') IS NULL
       OR to_regclass('public.model_execution_plans') IS NULL
       OR to_regclass('public.chat_turns') IS NULL
       OR to_regclass('public.model_route_reserved_requests') IS NULL THEN
        RAISE EXCEPTION 'V54 requires V53 physical execution governance';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgrelid = 'public.model_execution_attempts'::regclass
          AND tgname = 'trg_model_execution_attempt_transition_v53'
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V54 requires V53 serialized attempt transition trigger';
    END IF;
    -- We do not silently delete/repair historical physical evidence.
    IF EXISTS (
        SELECT 1 FROM public.model_execution_attempts a
        WHERE (
            CASE
                WHEN a.usage_status IS NULL THEN
                    a.input_tokens IS NULL
                    AND a.cached_input_tokens IS NULL
                    AND a.cache_write_input_tokens IS NULL
                    AND a.output_tokens IS NULL
                    AND a.specialized_dimensions_present IS NULL
                    AND a.specialized_dimensions_valid IS NULL
                WHEN a.usage_status IN ('AVAILABLE', 'PARTIAL', 'MISSING') THEN
                    a.specialized_dimensions_present IS NOT NULL
                    AND a.specialized_dimensions_valid IS NOT NULL
                    AND (a.specialized_dimensions_present IS TRUE
                         OR (a.specialized_dimensions_valid IS TRUE
                             AND a.cached_input_tokens IS NULL
                             AND a.cache_write_input_tokens IS NULL))
                    AND (
                        (a.usage_status = 'AVAILABLE'
                         AND a.input_tokens IS NOT NULL
                         AND a.output_tokens IS NOT NULL)
                        OR (a.usage_status = 'PARTIAL'
                            AND ((a.input_tokens IS NULL AND a.output_tokens IS NOT NULL)
                                 OR (a.input_tokens IS NOT NULL AND a.output_tokens IS NULL)))
                        OR (a.usage_status = 'MISSING'
                            AND a.input_tokens IS NULL AND a.output_tokens IS NULL)
                    )
                ELSE FALSE
            END
        ) IS DISTINCT FROM TRUE
    ) THEN
        RAISE EXCEPTION 'V54 preflight: historical usage/specialized evidence violates strict Boolean lifecycle';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.model_execution_attempts
        WHERE pricing_status = 'PRICED' AND (cost_usd IS NULL OR cost_usd <= 0)
    ) THEN
        RAISE EXCEPTION 'V54 preflight: PRICED physical attempt has non-positive cost';
    END IF;
END
$$;

-- V50 usage/specialized OR branches admitted SQL UNKNOWN. The new CASE must
-- evaluate to TRUE (not NULL); preserve all valid AVAILABLE/PARTIAL/MISSING
-- states, including legitimately absent specialized counters.
ALTER TABLE public.model_execution_attempts
    ADD CONSTRAINT chk_model_execution_attempt_usage_evidence_strict_v54
    CHECK ((CASE
        WHEN usage_status IS NULL THEN
            input_tokens IS NULL AND cached_input_tokens IS NULL
            AND cache_write_input_tokens IS NULL AND output_tokens IS NULL
            AND specialized_dimensions_present IS NULL
            AND specialized_dimensions_valid IS NULL
        WHEN usage_status IN ('AVAILABLE', 'PARTIAL', 'MISSING') THEN
            specialized_dimensions_present IS NOT NULL
            AND specialized_dimensions_valid IS NOT NULL
            AND (specialized_dimensions_present IS TRUE
                 OR (specialized_dimensions_valid IS TRUE
                     AND cached_input_tokens IS NULL
                     AND cache_write_input_tokens IS NULL))
            AND (
                (usage_status = 'AVAILABLE'
                 AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL)
                OR (usage_status = 'PARTIAL'
                    AND ((input_tokens IS NULL AND output_tokens IS NOT NULL)
                         OR (input_tokens IS NOT NULL AND output_tokens IS NULL)))
                OR (usage_status = 'MISSING'
                    AND input_tokens IS NULL AND output_tokens IS NULL)
            )
        ELSE FALSE
    END) IS TRUE) NOT VALID;
ALTER TABLE public.model_execution_attempts
    VALIDATE CONSTRAINT chk_model_execution_attempt_usage_evidence_strict_v54;

ALTER TABLE public.model_execution_attempts
    ADD CONSTRAINT chk_model_execution_attempt_priced_positive_v54
    CHECK (pricing_status IS DISTINCT FROM 'PRICED'
           OR (cost_usd IS NOT NULL AND cost_usd > 0)) NOT VALID;
ALTER TABLE public.model_execution_attempts
    VALIDATE CONSTRAINT chk_model_execution_attempt_priced_positive_v54;

-- Plan creation and physical attempt are authorized only during PROCESSING,
-- after the existing fenced markProviderCallStarted. Explicitly lock ChatTurn
-- so concurrent recovery/finalization cannot race this check at READ COMMITTED.
CREATE FUNCTION public.enforce_model_execution_plan_live_turn_v54()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_state varchar(32);
    v_started timestamptz;
BEGIN
    SELECT state, provider_call_started_at
    INTO v_state, v_started
    FROM public.chat_turns WHERE id = NEW.chat_turn_id
    FOR SHARE;
    IF NOT FOUND OR v_state IS DISTINCT FROM 'PROCESSING'
       OR v_started IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V54 provider plan requires PROCESSING ChatTurn after fenced call-start intent';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_model_execution_plan_live_turn_v54
BEFORE INSERT ON public.model_execution_plans
FOR EACH ROW EXECUTE FUNCTION public.enforce_model_execution_plan_live_turn_v54();

CREATE FUNCTION public.enforce_model_execution_attempt_live_turn_v54()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_state varchar(32);
    v_started timestamptz;
BEGIN
    SELECT t.state, t.provider_call_started_at
    INTO v_state, v_started
    FROM public.model_execution_plans p
    JOIN public.chat_turns t ON t.id = p.chat_turn_id
    WHERE p.id = NEW.execution_plan_id
    FOR SHARE OF t;
    IF NOT FOUND OR v_state IS DISTINCT FROM 'PROCESSING'
       OR v_started IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V54 new physical call is forbidden after logical ChatTurn left PROCESSING';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_model_execution_attempt_live_turn_v54
BEFORE INSERT ON public.model_execution_attempts
FOR EACH ROW EXECUTE FUNCTION public.enforce_model_execution_attempt_live_turn_v54();

-- Read-only incident detection. This does not rewrite or fabricate terminal
-- evidence. A physical SUCCEEDED response may lack persisted text if chat
-- finalization rolled back or JVM died; automatic ChatTurn replay is unsafe.
CREATE VIEW public.model_execution_reconciliation_queue_v54 AS
SELECT
    p.id AS execution_plan_id,
    p.provider_operation_id,
    p.chat_turn_id,
    p.organization_id,
    a.id AS execution_attempt_id,
    a.provider_attempt_id,
    a.attempt_number,
    a.provider_type,
    a.requested_physical_model,
    a.resolved_physical_model,
    a.outcome AS physical_outcome,
    a.outcome_certainty,
    a.usage_status,
    a.pricing_status,
    a.cost_usd,
    a.started_at,
    a.finished_at,
    t.state AS chat_turn_state,
    CASE
        WHEN a.outcome = 'STARTED' THEN 'PHYSICAL_STARTED_UNRESOLVED'
        WHEN a.outcome = 'AMBIGUOUS' THEN 'PHYSICAL_OUTCOME_UNKNOWN'
        WHEN a.outcome = 'SUCCEEDED' AND t.state <> 'SUCCEEDED'
            THEN 'PROVIDER_SUCCEEDED_CHAT_NOT_SUCCEEDED'
        WHEN a.outcome = 'SUCCEEDED'
             AND a.pricing_status IN ('UNPRICED', 'CALCULATION_FAILED')
            THEN 'PROVIDER_SUCCEEDED_COST_UNKNOWN'
        WHEN a.outcome = 'FAILED' AND a.outcome_certainty <> 'KNOWN_NOT_EXECUTED'
             AND a.pricing_status IS DISTINCT FROM 'PRICED'
             AND a.pricing_status IS DISTINCT FROM 'FREE'
            THEN 'FAILED_CALL_COST_NOT_PROVEN'
        ELSE 'NONE'
    END AS reconciliation_reason
FROM public.model_execution_attempts a
JOIN public.model_execution_plans p ON p.id = a.execution_plan_id
JOIN public.chat_turns t ON t.id = p.chat_turn_id
WHERE a.outcome IN ('STARTED', 'AMBIGUOUS')
   OR (a.outcome = 'SUCCEEDED' AND (
       t.state <> 'SUCCEEDED'
       OR a.pricing_status IN ('UNPRICED', 'CALCULATION_FAILED')))
   OR (a.outcome = 'FAILED'
       AND a.outcome_certainty <> 'KNOWN_NOT_EXECUTED'
       AND a.pricing_status IS DISTINCT FROM 'PRICED'
       AND a.pricing_status IS DISTINCT FROM 'FREE');

COMMENT ON VIEW public.model_execution_reconciliation_queue_v54 IS
'Read-only physical execution incidents; never use this view to auto-resubmit a ChatTurn.';
