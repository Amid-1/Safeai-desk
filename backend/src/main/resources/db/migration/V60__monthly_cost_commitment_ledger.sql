-- backend/src/main/resources/db/migration/V60__monthly_cost_commitment_ledger.sql
-- Versioned accounting read model. Immutable decision V1/V2/V3 hashes and
-- historical monetary snapshots are unchanged. Financial month uses UTC.
-- 'actual' means provider-evidenced cost, not an invoice settlement claim.
CREATE VIEW public.model_cost_commitment_ledger_v60 AS
WITH attempt_facts AS (
    SELECT p.organization_id, p.model_route_decision_id, a.id AS execution_attempt_id,
           a.started_at AS ledger_at, a.outcome,
           (a.outcome <> 'STARTED' AND a.usage_status = 'AVAILABLE'
             AND a.specialized_dimensions_valid IS TRUE
             AND a.pricing_status IN ('PRICED', 'FREE') AND a.cost_usd IS NOT NULL) AS exact,
           a.outcome_certainty, a.cost_usd,
           d.estimated_max_cost_usd, d.pricing_complete
    FROM public.model_execution_attempts a
    JOIN public.model_execution_plans p ON p.id = a.execution_plan_id
    LEFT JOIN public.model_route_decisions d ON d.id = p.model_route_decision_id
), physical AS (
    SELECT organization_id, model_route_decision_id, execution_attempt_id, ledger_at,
           'PHYSICAL_ATTEMPT'::text AS source,
           CASE WHEN exact THEN cost_usd ELSE 0::numeric END AS actual_cost_usd,
           CASE WHEN outcome = 'STARTED' AND pricing_complete
                THEN coalesce(estimated_max_cost_usd, 0) ELSE 0::numeric END AS reserved_cost_usd,
           CASE WHEN exact OR (outcome = 'FAILED' AND outcome_certainty = 'KNOWN_NOT_EXECUTED')
                     OR (outcome = 'STARTED' AND pricing_complete AND estimated_max_cost_usd IS NOT NULL)
                THEN 0::bigint ELSE 1::bigint END AS unknown_cost_count,
           outcome = 'STARTED' AS carry_forward
    FROM attempt_facts
), pending AS (
    -- Reservation remains in force over midnight/month end before provider I/O.
    -- Once STARTED is durable its row owns the physical reservation; do not
    -- also count that pending row. A succeeded physical call needs no next call.
    SELECT d.organization_id, d.id AS model_route_decision_id,
           NULL::uuid AS execution_attempt_id, d.created_at AS ledger_at,
           'PENDING_RESERVATION'::text AS source, 0::numeric AS actual_cost_usd,
           CASE WHEN d.pricing_complete THEN coalesce(d.estimated_max_cost_usd, 0)
                ELSE 0::numeric END AS reserved_cost_usd,
           CASE WHEN d.pricing_complete AND d.estimated_max_cost_usd IS NOT NULL
                THEN 0::bigint ELSE 1::bigint END AS unknown_cost_count,
           true AS carry_forward
    FROM public.model_route_decisions d
    JOIN public.chat_turns t ON t.model_route_decision_id = d.id
    LEFT JOIN public.model_execution_plans p ON p.model_route_decision_id = d.id AND p.chat_turn_id = t.id
    WHERE d.outcome = 'ALLOWED' AND t.state = 'PROCESSING'
      AND NOT EXISTS (SELECT 1 FROM public.model_execution_attempts a
                      WHERE a.execution_plan_id = p.id AND a.outcome IN ('STARTED', 'SUCCEEDED'))
), missing_evidence AS (
    -- A terminal turn with a plan but no matching attempt must never disappear
    -- as zero cost merely because durable physical evidence is missing.
    SELECT d.organization_id, d.id AS model_route_decision_id,
           NULL::uuid AS execution_attempt_id, d.created_at AS ledger_at,
           'MISSING_PHYSICAL_EVIDENCE'::text AS source, 0::numeric AS actual_cost_usd,
           0::numeric AS reserved_cost_usd, 1::bigint AS unknown_cost_count, false AS carry_forward
    FROM public.model_route_decisions d
    JOIN public.chat_turns t ON t.model_route_decision_id = d.id
    JOIN public.model_execution_plans p ON p.model_route_decision_id = d.id AND p.chat_turn_id = t.id
    WHERE d.outcome = 'ALLOWED' AND t.state IN ('SUCCEEDED', 'AMBIGUOUS')
      AND NOT EXISTS (SELECT 1 FROM public.model_execution_attempts a
                      WHERE a.execution_plan_id = p.id
                        AND ((t.state = 'SUCCEEDED' AND a.outcome = 'SUCCEEDED')
                             OR (t.state = 'AMBIGUOUS' AND a.outcome IN ('STARTED', 'AMBIGUOUS', 'SUCCEEDED'))))
), legacy AS (
    -- Pre-physical-ledger history has no reliable physical start timestamp.
    -- Keep its original route timestamp explicitly labelled LEGACY rather
    -- than inventing historical provider activity.
    SELECT d.organization_id, d.id AS model_route_decision_id,
           NULL::uuid AS execution_attempt_id, d.created_at AS ledger_at,
           'LEGACY_ROUTE_TIMESTAMP'::text AS source,
           CASE WHEN t.state = 'SUCCEEDED' AND m.usage_status = 'AVAILABLE'
                     AND m.pricing_status IN ('PRICED', 'FREE') AND m.cost_usd IS NOT NULL
                THEN m.cost_usd ELSE 0::numeric END AS actual_cost_usd,
           0::numeric AS reserved_cost_usd,
           CASE WHEN t.state = 'SUCCEEDED' AND m.usage_status = 'AVAILABLE'
                     AND m.pricing_status IN ('PRICED', 'FREE') AND m.cost_usd IS NOT NULL
                THEN 0::bigint ELSE 1::bigint END AS unknown_cost_count,
           false AS carry_forward
    FROM public.model_route_decisions d
    JOIN public.chat_turns t ON t.model_route_decision_id = d.id
    LEFT JOIN public.chat_messages m ON m.id = t.assistant_message_id
    WHERE d.outcome = 'ALLOWED' AND t.state IN ('SUCCEEDED', 'FAILED', 'AMBIGUOUS')
      AND NOT EXISTS (SELECT 1 FROM public.model_execution_plans p
                      WHERE p.model_route_decision_id = d.id AND p.chat_turn_id = t.id)
)
SELECT * FROM physical UNION ALL SELECT * FROM pending
UNION ALL SELECT * FROM missing_evidence UNION ALL SELECT * FROM legacy;

COMMENT ON VIEW public.model_cost_commitment_ledger_v60 IS
'UTC month by physical started_at; separately exposes evidenced cost, active upper-bound reservations and unknown liability. Active reservations carry over month boundaries; legacy timestamps are explicit.';
CREATE INDEX idx_execution_attempts_monthly_cost_v60
    ON public.model_execution_attempts(execution_plan_id, started_at);
