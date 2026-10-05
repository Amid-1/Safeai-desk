-- backend/src/main/resources/db/migration/V59__governance_integrity_hardening.sql
-- Additive hardening after V58. Do not edit or replace applied V45-V58.
-- If an earlier, different V59 was applied, do NOT overwrite it: allocate new
-- unused migration versions to these files before deploying.

CREATE FUNCTION public.valid_model_key_array_v59(keys text[])
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT NOT EXISTS (
        SELECT 1 FROM unnest(keys) AS k(value)
        WHERE value IS NULL OR value !~ '^[a-z0-9][a-z0-9._:/-]{0,159}$'
    )
$$;
ALTER TABLE public.organization_model_policies
    ADD CONSTRAINT chk_policy_individual_model_keys_v59 CHECK (
        public.valid_model_key_array_v59(allow_model_keys)
        AND public.valid_model_key_array_v59(deny_model_keys)
    ) NOT VALID;
ALTER TABLE public.organization_model_policies
    VALIDATE CONSTRAINT chk_policy_individual_model_keys_v59;

-- Round each nonnegative input/output component upward to 12 decimals.
ALTER TABLE public.model_catalog_entries
    ADD CONSTRAINT chk_catalog_upper_cost_numeric_v59 CHECK (
        input_usd_per_1m_tokens IS NULL OR output_usd_per_1m_tokens IS NULL OR
        (ceil(greatest(input_usd_per_1m_tokens, cached_input_usd_per_1m_tokens,
                       cache_write_input_usd_per_1m_tokens) * max_input_tokens * 1000000)
         + ceil(output_usd_per_1m_tokens * max_output_tokens * 1000000)) / 1000000000000
            < 1000000000000000000
    ) NOT VALID;
ALTER TABLE public.model_catalog_entries VALIDATE CONSTRAINT chk_catalog_upper_cost_numeric_v59;
ALTER TABLE public.model_catalog_entries
    ADD CONSTRAINT chk_catalog_pricing_version_controls_v59 CHECK (
        pricing_version IS NULL OR pricing_version !~ '[[:cntrl:]]'
    ) NOT VALID;
ALTER TABLE public.model_catalog_entries VALIDATE CONSTRAINT chk_catalog_pricing_version_controls_v59;

CREATE OR REPLACE FUNCTION public.enforce_model_execution_attempt_transition_v53()
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

    IF (v_decision.budget_enforcement = 'HARD' AND v_decision.monthly_budget_usd IS NOT NULL)
       OR EXISTS (
           SELECT 1 FROM public.organization_model_policies p
           WHERE p.id = v_decision.policy_id AND p.version = v_decision.policy_version
             AND p.organization_id = v_decision.organization_id
             AND p.enabled AND p.max_request_cost_usd IS NOT NULL
       ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V59 bounded-cost route permits exactly one physical attempt';
    END IF;

    IF v_previous.outcome IS DISTINCT FROM 'FAILED'
       OR (v_previous.outcome_certainty IS DISTINCT FROM 'KNOWN_NOT_EXECUTED'
           AND v_previous.outcome_certainty IS DISTINCT FROM 'KNOWN_REJECTED')
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
CREATE OR REPLACE VIEW public.model_execution_reconciliation_queue_v54 AS
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
        WHEN a.outcome = 'FAILED' AND a.outcome_certainty IS DISTINCT FROM 'KNOWN_NOT_EXECUTED'
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
       AND a.outcome_certainty IS DISTINCT FROM 'KNOWN_NOT_EXECUTED'
       AND a.pricing_status IS DISTINCT FROM 'PRICED'
       AND a.pricing_status IS DISTINCT FROM 'FREE');

COMMENT ON VIEW public.model_execution_reconciliation_queue_v54 IS
'Read-only physical execution incidents; never use this view to auto-resubmit a ChatTurn.';

-- Backfill in V57 already happened. Every NEW generation starts ACTIVE.
CREATE FUNCTION public.guard_knowledge_generation_insert_v59()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state IS DISTINCT FROM 'ACTIVE'
       OR NEW.retired_at IS NOT NULL OR NEW.collected_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V59 generation must be inserted ACTIVE without retirement/collection timestamps';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_generation_insert_guard_v59
BEFORE INSERT ON public.knowledge_index_generations
FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_generation_insert_v59();

ALTER TABLE public.knowledge_index_generations
    ADD CONSTRAINT chk_generation_retirement_chronology_v59 CHECK (
        retired_at IS NULL OR retired_at >= published_at
    ) NOT VALID;
ALTER TABLE public.knowledge_index_generations
    VALIDATE CONSTRAINT chk_generation_retirement_chronology_v59;

-- Preserve all existing title text/provenance; a historical heuristic cannot
-- be reversed without independent user/audit evidence.
CREATE FUNCTION public.guard_chat_title_provenance_v59()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.title_source = 'USER' AND NEW.title_source IS DISTINCT FROM 'USER'
       OR OLD.title_source = 'AUTO' AND NEW.title_source = 'DEFAULT' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'V59 chat title provenance cannot move backwards';
    END IF;
    -- Older nodes edit title without knowing title_source. Preserve that edit
    -- as USER so a future automatic generation cannot overwrite it.
    IF NEW.title IS DISTINCT FROM OLD.title
       AND NEW.title_source IS NOT DISTINCT FROM OLD.title_source THEN
        NEW.title_source := 'USER';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_chat_title_provenance_v59
BEFORE UPDATE OF title, title_source ON public.chat_sessions
FOR EACH ROW EXECUTE FUNCTION public.guard_chat_title_provenance_v59();
