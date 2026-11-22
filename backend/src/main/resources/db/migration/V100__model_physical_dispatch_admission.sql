-- V100: independent financial fence and durable one-shot dispatch authority.
-- No backfill: an old STARTED is evidence of uncertainty, not a dispatch receipt.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

CREATE TABLE public.model_physical_dispatch_receipts_v100 (
    attempt_id uuid PRIMARY KEY REFERENCES public.model_execution_attempts(id) ON DELETE RESTRICT,
    -- Deliberately invalid default: only the BEFORE INSERT admission trigger
    -- may replace it with a finite timestamp. Without that trigger, CHECK fails.
    authorized_at timestamptz NOT NULL DEFAULT '-infinity'::timestamptz
        CHECK (isfinite(authorized_at))
);
REVOKE ALL ON public.model_physical_dispatch_receipts_v100 FROM PUBLIC;

-- Historical attempts remain NULL. New attempts participate in the receipt protocol.
ALTER TABLE public.model_execution_attempts ADD COLUMN dispatch_protocol_version smallint
    CONSTRAINT chk_model_dispatch_protocol_v100 CHECK (dispatch_protocol_version = 1);

-- Same key as the 7.5E.2 routing and policy publication paths.
-- Existing STARTED triggers can hold quota/turn/organization locks. Never
-- wait on the reverse lock order: contention denies physical admission.
CREATE FUNCTION public.lock_model_admission_v100(org uuid)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp SET lock_timeout = '5s' AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING ERRCODE = '25000', MESSAGE = 'V100 model admission requires READ COMMITTED';
    END IF;
    IF org IS NULL OR NOT pg_try_advisory_xact_lock(hashtextextended('safeai:model-budget:' || org::text, 0)) THEN
        RAISE EXCEPTION USING ERRCODE = '55P03', MESSAGE = 'V100 model admission contention';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION public.lock_model_admission_v100(uuid) FROM PUBLIC;

CREATE FUNCTION public.guard_model_admission_publication_v100()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.lock_model_admission_v100(NEW.organization_id);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_model_admission_publication_v100() FROM PUBLIC;
CREATE TRIGGER trg_model_policy_admission_v100
BEFORE INSERT ON public.organization_model_policies
FOR EACH ROW EXECUTE FUNCTION public.guard_model_admission_publication_v100();
CREATE TRIGGER trg_model_route_admission_v100
BEFORE INSERT ON public.model_route_decisions
FOR EACH ROW WHEN (NEW.outcome = 'ALLOWED')
EXECUTE FUNCTION public.guard_model_admission_publication_v100();

CREATE FUNCTION public.assert_model_physical_admission_v100(plan_id uuid, started timestamptz,
                                                             physical_attempt_id uuid)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp SET lock_timeout = '5s' AS $$
DECLARE
    plan_row public.model_execution_plans%ROWTYPE;
    turn_row public.chat_turns%ROWTYPE;
    decision_row public.model_route_decisions%ROWTYPE;
    policy_row public.organization_model_policies%ROWTYPE;
    catalog_row public.model_catalog_entries%ROWTYPE;
    org_enabled boolean;
    org_epoch bigint;
    checked_at timestamptz;
    period_from timestamptz;
    period_to timestamptz;
    known_commitment numeric;
    unknown_liability numeric;
    own_reservation_count bigint;
BEGIN
    SELECT * INTO STRICT plan_row FROM public.model_execution_plans WHERE id = plan_id;
    PERFORM public.lock_model_admission_v100(plan_row.organization_id);
    SELECT enabled, auth_version INTO STRICT org_enabled, org_epoch
      FROM public.organizations WHERE id = plan_row.organization_id FOR SHARE;
    SELECT * INTO STRICT turn_row FROM public.chat_turns WHERE id = plan_row.chat_turn_id FOR SHARE;
    SELECT * INTO STRICT decision_row FROM public.model_route_decisions WHERE id = plan_row.model_route_decision_id;
    SELECT * INTO policy_row FROM public.organization_model_policies
      WHERE organization_id = plan_row.organization_id ORDER BY version DESC LIMIT 1;
    checked_at := clock_timestamp();

    IF org_enabled IS DISTINCT FROM TRUE
        OR turn_row.authorized_organization_auth_version IS DISTINCT FROM org_epoch THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'v100_organization_epoch',
            MESSAGE = 'V100 current model authorization required';
    END IF;
    IF turn_row.organization_id IS DISTINCT FROM plan_row.organization_id
        OR turn_row.model_route_decision_id IS DISTINCT FROM decision_row.id
        OR decision_row.organization_id IS DISTINCT FROM plan_row.organization_id
        OR decision_row.user_id IS DISTINCT FROM turn_row.user_id
        OR decision_row.outcome IS DISTINCT FROM 'ALLOWED'
        OR decision_row.decision_integrity_version IS DISTINCT FROM 3
        OR turn_row.state IS DISTINCT FROM 'PROCESSING'
        OR turn_row.processing_token IS NULL
        OR turn_row.lease_until IS NULL OR turn_row.lease_until <= checked_at
        OR turn_row.provider_call_started_at IS NULL
        OR policy_row.id IS NULL
        OR decision_row.policy_id IS DISTINCT FROM policy_row.id
        OR decision_row.policy_version IS DISTINCT FROM policy_row.version THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 current model authorization required';
    END IF;

    IF policy_row.enabled AND (policy_row.require_complete_pricing
        OR policy_row.max_request_cost_usd IS NOT NULL
        OR (policy_row.budget_enforcement = 'HARD' AND policy_row.monthly_budget_usd IS NOT NULL)) THEN
        IF decision_row.pricing_complete IS DISTINCT FROM TRUE
            OR decision_row.estimated_max_cost_usd IS NULL
            OR decision_row.estimated_max_cost_usd < 0
            OR decision_row.estimated_max_cost_usd::text IN ('NaN','Infinity','-Infinity')
            OR (policy_row.max_request_cost_usd IS NOT NULL
                AND decision_row.estimated_max_cost_usd > policy_row.max_request_cost_usd) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 current model price authorization required';
        END IF;
        SELECT * INTO STRICT catalog_row FROM public.model_catalog_entries
          WHERE id = decision_row.selected_catalog_entry_id;
        IF catalog_row.pricing_scheme IS DISTINCT FROM 'FLAT_TOKEN_V1'
            OR catalog_row.pricing_complete IS DISTINCT FROM TRUE
            OR catalog_row.pricing_status NOT IN ('FREE','CONFIGURED')
            OR catalog_row.input_usd_per_1m_tokens IS NULL
            OR catalog_row.output_usd_per_1m_tokens IS NULL
            OR decision_row.estimated_input_tokens IS NULL
            OR decision_row.estimated_output_tokens IS NULL
            OR decision_row.estimated_max_cost_usd IS DISTINCT FROM
                (ceil(decision_row.estimated_input_tokens * greatest(
                    catalog_row.input_usd_per_1m_tokens, catalog_row.cached_input_usd_per_1m_tokens,
                    catalog_row.cache_write_input_usd_per_1m_tokens) * 1000000)
                 + ceil(decision_row.estimated_output_tokens * catalog_row.output_usd_per_1m_tokens
                    * 1000000)) * 0.000000000001::numeric THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 price envelope mismatch';
        END IF;
    END IF;

    IF policy_row.enabled AND policy_row.budget_enforcement = 'HARD'
        AND policy_row.monthly_budget_usd IS NOT NULL THEN
        period_from := date_trunc('month', checked_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
        period_to := (date_trunc('month', checked_at AT TIME ZONE 'UTC') + interval '1 month') AT TIME ZONE 'UTC';
        IF started IS NULL OR NOT isfinite(started) OR started < period_from OR started >= period_to
            OR decision_row.budget_enforcement IS DISTINCT FROM 'HARD'
            OR decision_row.monthly_budget_usd IS DISTINCT FROM policy_row.monthly_budget_usd
            OR decision_row.monthly_cost_known IS DISTINCT FROM TRUE
            OR decision_row.budget_exceeded IS DISTINCT FROM FALSE
            OR NOT EXISTS (SELECT 1 FROM public.model_route_budget_commitments_v64 b
                WHERE b.model_route_decision_id = decision_row.id AND b.cost_state = 'KNOWN_WITHIN'
                  AND b.actual_cost_usd IS NOT NULL AND b.reserved_cost_usd IS NOT NULL
                  AND b.unknown_cost_count = 0) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 current hard budget evidence required';
        END IF;
        -- Pending route before INSERT, exact STARTED attempt before dispatch.
        -- Both are already counted in V62: never add this reservation twice.
        SELECT coalesce(sum(actual_cost_usd) FILTER (WHERE NOT carry_forward), 0)
                   + coalesce(sum(reserved_cost_usd), 0),
               coalesce(sum(unknown_cost_count), 0),
               count(*) FILTER (WHERE model_route_decision_id = decision_row.id
                   AND execution_attempt_id IS NOT DISTINCT FROM physical_attempt_id
                   AND source = CASE WHEN physical_attempt_id IS NULL
                                     THEN 'PENDING_RESERVATION' ELSE 'PHYSICAL_ATTEMPT' END
                   AND carry_forward AND unknown_cost_count = 0
                   AND reserved_cost_usd = decision_row.estimated_max_cost_usd)
          INTO known_commitment, unknown_liability, own_reservation_count
          FROM public.model_cost_commitment_ledger_v62
         WHERE organization_id = plan_row.organization_id
           AND (carry_forward OR (ledger_at >= period_from AND ledger_at < period_to));
        IF own_reservation_count <> 1 OR unknown_liability <> 0
            OR known_commitment::text IN ('NaN','Infinity','-Infinity')
            OR known_commitment > policy_row.monthly_budget_usd THEN
            RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 current hard budget denied';
        END IF;
    END IF;
    IF turn_row.lease_until <= clock_timestamp()
        OR (period_to IS NOT NULL AND clock_timestamp() >= period_to) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 admission expired during check';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION public.assert_model_physical_admission_v100(uuid,timestamptz,uuid) FROM PUBLIC;

CREATE FUNCTION public.guard_model_physical_start_v100()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.assert_model_physical_admission_v100(NEW.execution_plan_id, NEW.started_at, NULL);
    NEW.dispatch_protocol_version := 1;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_model_physical_start_v100() FROM PUBLIC;
-- Run after V53/V54/V65/V72/V85/V95/V96; their errors and side effects are
-- unchanged. On V100 rejection the whole INSERT statement rolls back.
CREATE TRIGGER trg_zzz_model_physical_start_v100
BEFORE INSERT ON public.model_execution_attempts
FOR EACH ROW EXECUTE FUNCTION public.guard_model_physical_start_v100();

-- Settlement callers acquire the tenant lock BEFORE acquiring any attempt row.
-- Direct SQL writers still face the nonblocking trigger below, avoiding inversion.
CREATE FUNCTION public.lock_model_settlement_v100(provider_attempt uuid)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp SET lock_timeout = '5s' AS $$
DECLARE org uuid;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING ERRCODE = '25000', MESSAGE = 'V100 settlement requires READ COMMITTED';
    END IF;
    SELECT p.organization_id INTO STRICT org FROM public.model_execution_attempts a
        JOIN public.model_execution_plans p ON p.id = a.execution_plan_id
        WHERE a.provider_attempt_id = provider_attempt;
    PERFORM pg_advisory_xact_lock(hashtextextended('safeai:model-budget:' || org::text, 0));
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.lock_model_settlement_v100(uuid) FROM PUBLIC;

-- Serialize publication of a changed physical liability with hard admission.
-- A contended settlement cannot erase the existing STARTED reservation.
CREATE FUNCTION public.guard_model_settlement_v100()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    org uuid;
BEGIN
    IF NEW.dispatch_protocol_version IS DISTINCT FROM OLD.dispatch_protocol_version THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 dispatch protocol is immutable';
    END IF;
    IF OLD.outcome IS NOT DISTINCT FROM NEW.outcome THEN RETURN NEW; END IF;
    SELECT organization_id INTO STRICT org FROM public.model_execution_plans
     WHERE id = OLD.execution_plan_id;
    PERFORM public.lock_model_admission_v100(org);
    IF NEW.failure_type = 'DISPATCH_ABORTED' AND (
        OLD.dispatch_protocol_version IS DISTINCT FROM 1
        OR NEW.outcome IS DISTINCT FROM 'FAILED'
        OR NEW.outcome_certainty IS DISTINCT FROM 'KNOWN_NOT_EXECUTED'
        OR NEW.retry_safety IS DISTINCT FROM 'SAME_TARGET_RETRY_FORBIDDEN'
        OR EXISTS (SELECT 1 FROM public.model_physical_dispatch_receipts_v100 WHERE attempt_id = OLD.id)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 abort requires proof of no dispatch receipt';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_model_settlement_v100() FROM PUBLIC;
CREATE TRIGGER trg_zzz_model_settlement_v100
BEFORE UPDATE ON public.model_execution_attempts
FOR EACH ROW
EXECUTE FUNCTION public.guard_model_settlement_v100();

-- Revalidate the exact immutable prepared binding and current Knowledge lease
-- under locks held through receipt commit. No document text or tokens are copied.
CREATE FUNCTION public.assert_model_knowledge_dispatch_v100(physical_id uuid)
RETURNS timestamptz LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp SET lock_timeout = '5s' AS $$
DECLARE
    a public.model_execution_attempts%ROWTYPE;
    p public.model_execution_plans%ROWTYPE;
    b public.model_route_prepared_requests_v62%ROWTYPE;
    t public.chat_turns%ROWTYPE;
    permit public.knowledge_rag_execution_permits_v85%ROWTYPE;
    lease public.knowledge_rag_execution_attempt_leases_v85%ROWTYPE;
    source record;
BEGIN
    SELECT * INTO STRICT a FROM public.model_execution_attempts WHERE id = physical_id;
    SELECT * INTO STRICT p FROM public.model_execution_plans WHERE id = a.execution_plan_id;
    SELECT * INTO STRICT b FROM public.model_route_prepared_requests_v62 WHERE model_route_decision_id = p.model_route_decision_id;
    SELECT * INTO STRICT t FROM public.chat_turns WHERE id = p.chat_turn_id;
    IF t.knowledge_mode = 'GENERAL' AND b.knowledge_base_id IS NULL THEN RETURN NULL; END IF;
    IF t.knowledge_mode NOT IN ('KNOWLEDGE_ASSISTED', 'KNOWLEDGE_ONLY')
        OR b.binding_version IS DISTINCT FROM 2 OR b.knowledge_base_id IS NULL
        OR b.knowledge_base_id IS DISTINCT FROM t.knowledge_base_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 Knowledge binding required';
    END IF;
    SELECT * INTO STRICT permit FROM public.knowledge_rag_execution_permits_v85
        WHERE chat_turn_id = p.chat_turn_id AND organization_id = p.organization_id;
    PERFORM 1 FROM public.knowledge_bases WHERE id = b.knowledge_base_id
        AND organization_id = p.organization_id AND enabled AND lifecycle_state = 'AVAILABLE' FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 Knowledge base unavailable'; END IF;
    FOR source IN SELECT * FROM public.knowledge_rag_execution_permit_sources_v85
        WHERE permit_id = permit.id ORDER BY document_id, document_version_id LOOP
        PERFORM 1 FROM public.knowledge_documents d JOIN public.knowledge_document_versions v
            ON v.document_id = d.id AND v.organization_id = d.organization_id AND v.knowledge_base_id = d.knowledge_base_id
            WHERE d.id = source.document_id AND d.organization_id = p.organization_id
            AND d.knowledge_base_id = b.knowledge_base_id AND d.enabled AND d.lifecycle_state = 'AVAILABLE'
            AND v.id = source.document_version_id AND source.organization_id = p.organization_id
            AND source.knowledge_base_id = b.knowledge_base_id FOR SHARE OF d, v;
        IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 Knowledge source unavailable'; END IF;
    END LOOP;
    SELECT * INTO STRICT permit FROM public.knowledge_rag_execution_permits_v85 WHERE id = permit.id FOR SHARE;
    SELECT * INTO STRICT lease FROM public.knowledge_rag_execution_attempt_leases_v85
        WHERE permit_id = permit.id AND provider_attempt_id = a.provider_attempt_id
        AND attempt_number = a.attempt_number FOR SHARE;
    IF permit.knowledge_base_id IS DISTINCT FROM b.knowledge_base_id
        OR permit.retrieval_run_id IS DISTINCT FROM b.retrieval_run_id
        OR permit.context_sha256::text IS DISTINCT FROM b.rag_context_sha256
        OR permit.state <> 'ACTIVE' OR lease.state <> 'ACTIVE'
        OR least(permit.expires_at, lease.lease_until) <= clock_timestamp() THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 Knowledge authorization expired or revoked';
    END IF;
    RETURN least(permit.expires_at, lease.lease_until);
END;
$$;
REVOKE ALL ON FUNCTION public.assert_model_knowledge_dispatch_v100(uuid) FROM PUBLIC;

CREATE FUNCTION public.guard_model_dispatch_receipt_v100()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp SET lock_timeout = '5s' AS $$
DECLARE
    attempt_row public.model_execution_attempts%ROWTYPE;
    knowledge_until timestamptz;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 dispatch receipt is immutable';
    END IF;
    -- Preserve V96's quota-before-model lock ordering.
    PERFORM public.lock_quota_admission_v96();
    SELECT * INTO STRICT attempt_row FROM public.model_execution_attempts
     WHERE id = NEW.attempt_id FOR SHARE;
    IF attempt_row.outcome IS DISTINCT FROM 'STARTED' OR attempt_row.dispatch_protocol_version IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 dispatch requires STARTED';
    END IF;
    -- Retain the independent Chat quota gate. Recheck AFTER any lock wait.
    IF public.check_quota_dispatch_v96(attempt_row.execution_plan_id, attempt_row.provider_attempt_id)
        IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 quota dispatch denied';
    END IF;
    knowledge_until := public.assert_model_knowledge_dispatch_v100(attempt_row.id);
    -- Recheck quota after potentially contended Knowledge row locks.
    IF public.check_quota_dispatch_v96(attempt_row.execution_plan_id, attempt_row.provider_attempt_id)
        IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 quota dispatch denied';
    END IF;
    PERFORM public.assert_model_physical_admission_v100(
        attempt_row.execution_plan_id, attempt_row.started_at, attempt_row.id);
    -- Financial scans can cross a quota calendar boundary too.
    IF public.check_quota_dispatch_v96(attempt_row.execution_plan_id, attempt_row.provider_attempt_id)
        IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 quota dispatch denied';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.model_execution_plans p JOIN public.chat_turns t ON t.id=p.chat_turn_id
        WHERE p.id=attempt_row.execution_plan_id AND t.lease_until > clock_timestamp()) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 admission expired during check';
    END IF;
    IF EXISTS (SELECT 1 FROM public.model_execution_plans p JOIN public.model_route_decisions d ON d.id=p.model_route_decision_id
        JOIN public.organization_model_policies pol ON pol.id=d.policy_id
        WHERE p.id=attempt_row.execution_plan_id AND pol.enabled AND pol.budget_enforcement='HARD'
          AND pol.monthly_budget_usd IS NOT NULL AND date_trunc('month', attempt_row.started_at AT TIME ZONE 'UTC')
              IS DISTINCT FROM date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC')) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 admission expired during check';
    END IF;
    IF knowledge_until IS NOT NULL AND knowledge_until <= clock_timestamp() THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'V100 Knowledge authorization expired during admission';
    END IF;
    NEW.authorized_at := clock_timestamp();
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.guard_model_dispatch_receipt_v100() FROM PUBLIC;
CREATE TRIGGER trg_model_dispatch_receipt_v100
BEFORE INSERT OR UPDATE OR DELETE ON public.model_physical_dispatch_receipts_v100
FOR EACH ROW EXECUTE FUNCTION public.guard_model_dispatch_receipt_v100();

CREATE FUNCTION public.consume_model_dispatch_v100(plan_id uuid, provider_attempt uuid)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    physical_id uuid;
BEGIN
    SELECT id INTO STRICT physical_id FROM public.model_execution_attempts
     WHERE execution_plan_id = plan_id AND provider_attempt_id = provider_attempt AND outcome = 'STARTED';
    INSERT INTO public.model_physical_dispatch_receipts_v100(attempt_id)
        VALUES (physical_id);
    -- A duplicate raises 23505; no ON CONFLICT success or replay.
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.consume_model_dispatch_v100(uuid,uuid) FROM PUBLIC;

COMMENT ON TABLE public.model_physical_dispatch_receipts_v100 IS
    'One durable dispatch authorization per physical attempt; not proof of network delivery or provider execution.';
