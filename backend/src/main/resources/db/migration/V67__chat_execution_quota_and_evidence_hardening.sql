-- backend/src/main/resources/db/migration/V67__chat_execution_quota_and_evidence_hardening.sql
-- SafeAI Desk V67
-- Chat execution/quota/evidence hardening after V66.
--
-- V1-V66 are immutable.

DO $migration$
BEGIN
    IF to_regclass('public.chat_turns') IS NULL
       OR to_regclass('public.chat_messages') IS NULL
       OR to_regclass('public.chat_quota_reservations') IS NULL
       OR to_regclass('public.model_execution_attempts') IS NULL
       OR to_regclass('public.model_execution_plans') IS NULL THEN
        RAISE EXCEPTION
            'V67 requires Chat quota and physical execution schema';
    END IF;
END
$migration$;

-- ---------------------------------------------------------------------------
-- 1. ChatTurn is an evidence state machine, not merely a row-shape CHECK.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.guard_chat_turn_transition_v67()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.session_id IS DISTINCT FROM OLD.session_id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.client_request_id IS DISTINCT FROM OLD.client_request_id
       OR NEW.request_content_hash IS DISTINCT FROM OLD.request_content_hash
       OR NEW.provider_operation_id IS DISTINCT FROM OLD.provider_operation_id
       OR NEW.user_message_id IS DISTINCT FROM OLD.user_message_id
       OR NEW.model_route_decision_id IS DISTINCT FROM OLD.model_route_decision_id
       OR NEW.knowledge_base_id IS DISTINCT FROM OLD.knowledge_base_id
       OR NEW.knowledge_mode IS DISTINCT FROM OLD.knowledge_mode
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V67 immutable ChatTurn identity changed';
    END IF;

    IF OLD.state IN ('SUCCEEDED', 'FAILED', 'AMBIGUOUS') THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V67 terminal ChatTurn is immutable';
    END IF;

    IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
        (OLD.state = 'NEW' AND NEW.state = 'PROCESSING')
        OR
        (
            OLD.state = 'PROCESSING'
            AND NEW.state IN ('SUCCEEDED', 'FAILED', 'AMBIGUOUS')
        )
    ) THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V67 invalid ChatTurn state transition';
    END IF;

    IF OLD.provider_call_started_at IS NOT NULL
       AND NEW.provider_call_started_at IS DISTINCT FROM OLD.provider_call_started_at THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V67 provider_call_started_at is immutable once set';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_chat_turn_transition_v67
BEFORE UPDATE ON public.chat_turns
FOR EACH ROW
EXECUTE FUNCTION public.guard_chat_turn_transition_v67();

-- ---------------------------------------------------------------------------
-- 2. provider_call_started_at becomes physical-attempt evidence.
--
-- ExecutionPlan creation is legal for a PROCESSING turn without claiming that
-- provider I/O has started. The physical STARTED attempt transaction sets the
-- chat marker using PostgreSQL time before network I/O is allowed.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.enforce_model_execution_plan_live_turn_v54()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_state varchar(32);
BEGIN
    SELECT state
      INTO v_state
      FROM public.chat_turns
     WHERE id = NEW.chat_turn_id
     FOR SHARE;

    IF NOT FOUND OR v_state IS DISTINCT FROM 'PROCESSING' THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V67 provider plan requires PROCESSING ChatTurn';
    END IF;

    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION public.enforce_model_execution_attempt_live_turn_v54()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_turn_id uuid;
    v_state varchar(32);
    v_token uuid;
    v_lease timestamptz;
    v_now timestamptz;
BEGIN
    v_now := clock_timestamp();

    SELECT t.id, t.state, t.processing_token, t.lease_until
      INTO v_turn_id, v_state, v_token, v_lease
      FROM public.model_execution_plans p
      JOIN public.chat_turns t
        ON t.id = p.chat_turn_id
     WHERE p.id = NEW.execution_plan_id
     FOR UPDATE OF t;

    IF NOT FOUND
       OR v_state IS DISTINCT FROM 'PROCESSING'
       OR v_token IS NULL
       OR v_lease IS NULL
       OR v_lease <= v_now THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V67 physical attempt requires a live fenced PROCESSING ChatTurn';
    END IF;

    UPDATE public.chat_turns
       SET provider_call_started_at =
               COALESCE(provider_call_started_at, v_now),
           updated_at = GREATEST(updated_at, v_now),
           version = version + 1
     WHERE id = v_turn_id
       AND state = 'PROCESSING'
       AND processing_token = v_token
       AND lease_until > v_now;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V67 failed to fence ChatTurn at physical STARTED boundary';
    END IF;

    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------
-- 3. Completed message evidence cannot be rewritten by ORM/direct SQL.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.guard_completed_chat_message_update_v67()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'COMPLETED' THEN
        RAISE EXCEPTION USING ERRCODE='23514',
            MESSAGE='V67 completed chat message evidence is immutable';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_completed_chat_message_update_v67
BEFORE UPDATE ON public.chat_messages
FOR EACH ROW
EXECUTE FUNCTION public.guard_completed_chat_message_update_v67();

-- ---------------------------------------------------------------------------
-- 4. Quota reservation provenance.
-- ---------------------------------------------------------------------------

ALTER TABLE public.chat_quota_reservations
    ADD COLUMN model_route_decision_id uuid
        REFERENCES public.model_route_decisions(id)
        ON DELETE RESTRICT,
    ADD COLUMN reservation_version integer,
    ADD COLUMN cost_verifiable boolean;

UPDATE public.chat_quota_reservations
SET reservation_version = 1,
    cost_verifiable = false
WHERE reservation_version IS NULL;

ALTER TABLE public.chat_quota_reservations
    ALTER COLUMN reservation_version SET NOT NULL,
    ALTER COLUMN cost_verifiable SET NOT NULL;

ALTER TABLE public.chat_quota_reservations
    ADD CONSTRAINT chk_chat_quota_reservation_version_v67
    CHECK (reservation_version IN (1, 2)) NOT VALID;

ALTER TABLE public.chat_quota_reservations
    VALIDATE CONSTRAINT chk_chat_quota_reservation_version_v67;

ALTER TABLE public.chat_quota_reservations
    ADD CONSTRAINT chk_chat_quota_route_binding_v67
    CHECK (
        reservation_version = 1
        OR (
            reservation_version = 2
            AND model_route_decision_id IS NOT NULL
        )
    ) NOT VALID;

ALTER TABLE public.chat_quota_reservations
    VALIDATE CONSTRAINT chk_chat_quota_route_binding_v67;

COMMENT ON COLUMN public.chat_quota_reservations.reservation_version IS
'1=legacy static reservation; 2=route-bound reservation from immutable ModelRoute decision/envelope.';

COMMENT ON COLUMN public.chat_quota_reservations.cost_verifiable IS
'True only when reserved_cost_usd is a proven upper bound under a supported immutable pricing scheme.';

CREATE VIEW public.chat_physical_spend_v67 AS
SELECT
    p.chat_turn_id AS turn_id,
    COALESCE(
        sum(a.input_tokens) FILTER (
            WHERE a.outcome IN ('SUCCEEDED', 'PROTOCOL_REJECTED')
              AND a.outcome_certainty = 'KNOWN_EXECUTED'
              AND a.input_tokens IS NOT NULL
        ),
        0
    )::bigint AS known_input_tokens,
    COALESCE(
        sum(a.output_tokens) FILTER (
            WHERE a.outcome IN ('SUCCEEDED', 'PROTOCOL_REJECTED')
              AND a.outcome_certainty = 'KNOWN_EXECUTED'
              AND a.output_tokens IS NOT NULL
        ),
        0
    )::bigint AS known_output_tokens,
    COALESCE(
        sum(a.cost_usd) FILTER (
            WHERE a.outcome IN ('SUCCEEDED', 'PROTOCOL_REJECTED')
              AND a.outcome_certainty = 'KNOWN_EXECUTED'
              AND a.pricing_status IN ('PRICED', 'FREE')
        ),
        0
    )::numeric(30,12) AS known_cost_usd,
    COALESCE(
        bool_or(
            a.outcome IN ('SUCCEEDED', 'PROTOCOL_REJECTED')
            AND a.outcome_certainty = 'KNOWN_EXECUTED'
            AND a.usage_status IS DISTINCT FROM 'AVAILABLE'
        ),
        false
    ) AS usage_unverifiable,
    COALESCE(
        bool_or(
            a.outcome IN ('SUCCEEDED', 'PROTOCOL_REJECTED')
            AND a.outcome_certainty = 'KNOWN_EXECUTED'
            AND a.pricing_status IS DISTINCT FROM 'PRICED'
            AND a.pricing_status IS DISTINCT FROM 'FREE'
        ),
        false
    ) AS cost_unverifiable
FROM public.model_execution_plans p
JOIN public.model_execution_attempts a
  ON a.execution_plan_id = p.id
GROUP BY p.chat_turn_id;

COMMENT ON VIEW public.chat_physical_spend_v67 IS
'Authoritative known physical provider usage/spend per ChatTurn; flags executed attempts whose usage/cost cannot be proven.';

-- ---------------------------------------------------------------------------
-- 5. Database wall-clock source for distributed lease/fencing code.
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.safeai_clock_timestamp_v67()
RETURNS timestamptz
LANGUAGE sql
VOLATILE
AS $$
    SELECT clock_timestamp()
$$;

COMMENT ON FUNCTION public.safeai_clock_timestamp_v67() IS
'Database wall-clock source for distributed Chat lease/fencing operations.';
