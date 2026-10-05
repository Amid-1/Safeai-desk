-- backend/src/main/resources/db/migration/V62__model_control_plane_hardening.sql
-- SafeAI Desk V62 — Model Control Plane hardening and commitment model v2.
--
-- Additive only. V45-V61 are immutable and MUST NOT be edited after deployment.
-- This migration:
--   1) makes budget exactness independent from a budget overrun already proven
--      by known commitments;
--   2) supports application-layer fail-closed handling for absent policies;
--   3) hardens required-capability array canonicality and catalog display text;
--   4) adds durable binding for the fully materialized request;
--   5) replaces the V60 commitment read model with a V62 version that reserves
--      a next physical attempt only when such an attempt is actually retry-safe.

-- ---------------------------------------------------------------------------
-- 0. Baseline / drift preflight.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF to_regclass('public.model_route_decisions') IS NULL
       OR to_regclass('public.model_catalog_entries') IS NULL
       OR to_regclass('public.organization_model_policies') IS NULL
       OR to_regclass('public.model_execution_plans') IS NULL
       OR to_regclass('public.model_execution_attempts') IS NULL
       OR to_regclass('public.chat_turns') IS NULL
       OR to_regclass('public.model_route_reserved_requests') IS NULL THEN
        RAISE EXCEPTION 'Cannot apply V62: required V45-V61 tables are missing';
    END IF;

    IF to_regclass('public.model_cost_commitment_ledger_v60') IS NULL THEN
        RAISE EXCEPTION 'Cannot apply V62: V60 commitment ledger is missing';
    END IF;

    IF to_regprocedure('public.reject_model_control_plane_mutation_v45()') IS NULL THEN
        RAISE EXCEPTION 'Cannot apply V62: V45 immutable-provenance trigger function is missing';
    END IF;

    IF EXISTS (
        SELECT required.column_name
        FROM (VALUES
            ('model_route_decision_id'),
            ('chat_turn_id'),
            ('provider_operation_id'),
            ('base_request_sha256')
        ) AS required(column_name)
        WHERE NOT EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = 'public'
              AND c.table_name = 'model_route_reserved_requests'
              AND c.column_name = required.column_name
        )
    ) THEN
        RAISE EXCEPTION 'Cannot apply V62: sealed-request table contract is incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.model_route_decisions'::regclass
          AND conname = 'chk_model_route_decisions_budget_snapshot'
    ) THEN
        RAISE EXCEPTION 'Cannot apply V62: V45 budget snapshot constraint is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.model_route_decisions'::regclass
          AND conname = 'chk_model_route_decisions_reason'
    ) OR NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.model_route_decisions'::regclass
          AND conname = 'chk_model_route_decisions_reason_semantics'
    ) THEN
        RAISE EXCEPTION 'Cannot apply V62: route-reason constraints are missing';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. Budget-state representability.
--
-- V45 made these two facts mutually exclusive:
--   monthly_cost_known = false
--   budget_exceeded = true
-- because budget_exceeded required monthly_projected_usd while unknown cost
-- required monthly_projected_usd to be null.
--
-- V62 keeps the exact projected amount null when liability is incomplete, but
-- permits budget_exceeded=true when the known committed amount already proves
-- an overrun. No historical decision is rewritten and the existing v1/v2/v3
-- digest formats remain unchanged.
-- ---------------------------------------------------------------------------
ALTER TABLE public.model_route_decisions
    DROP CONSTRAINT chk_model_route_decisions_budget_snapshot;

ALTER TABLE public.model_route_decisions
    ADD CONSTRAINT chk_model_route_decisions_budget_snapshot
    CHECK (
        (
            NOT budget_exceeded
            OR (
                monthly_budget_usd IS NOT NULL
                AND (
                    (
                        monthly_cost_known
                        AND monthly_projected_usd IS NOT NULL
                        AND monthly_projected_usd > monthly_budget_usd
                    )
                    OR
                    (
                        NOT monthly_cost_known
                        AND monthly_projected_usd IS NULL
                        AND monthly_spent_usd IS NOT NULL
                        AND (
                            monthly_spent_usd > monthly_budget_usd
                            OR (
                                pricing_complete
                                AND estimated_max_cost_usd IS NOT NULL
                                AND monthly_spent_usd + estimated_max_cost_usd
                                    > monthly_budget_usd
                            )
                        )
                    )
                )
            )
        )
        AND (
            monthly_cost_known
            OR monthly_projected_usd IS NULL
        )
        AND (
            monthly_projected_usd IS NULL
            OR (
                monthly_spent_usd IS NOT NULL
                AND estimated_max_cost_usd IS NOT NULL
                AND monthly_projected_usd =
                    monthly_spent_usd + estimated_max_cost_usd
            )
        )
    ) NOT VALID;

ALTER TABLE public.model_route_decisions
    VALIDATE CONSTRAINT chk_model_route_decisions_budget_snapshot;

ALTER TABLE public.model_route_decisions
    ADD CONSTRAINT chk_model_route_decisions_budget_reason_v62
    CHECK (
        reason <> 'MONTHLY_BUDGET_EXCEEDED'
        OR (
            outcome = 'DENIED'
            AND budget_enforcement = 'HARD'
            AND budget_exceeded
            AND monthly_budget_usd IS NOT NULL
        )
    ) NOT VALID;

ALTER TABLE public.model_route_decisions
    VALIDATE CONSTRAINT chk_model_route_decisions_budget_reason_v62;

ALTER TABLE public.model_route_decisions
    ADD CONSTRAINT chk_model_route_decisions_budget_unverifiable_v62
    CHECK (
        reason <> 'MONTHLY_BUDGET_UNVERIFIABLE'
        OR (
            outcome = 'DENIED'
            AND budget_enforcement = 'HARD'
            AND NOT monthly_cost_known
            AND NOT budget_exceeded
            AND monthly_budget_usd IS NOT NULL
            AND monthly_projected_usd IS NULL
        )
    ) NOT VALID;

ALTER TABLE public.model_route_decisions
    VALIDATE CONSTRAINT chk_model_route_decisions_budget_unverifiable_v62;

COMMENT ON CONSTRAINT chk_model_route_decisions_budget_snapshot
ON public.model_route_decisions IS
'Exact monthly cost and proven budget overrun are independent. Unknown liability keeps monthly_projected_usd null, but known commitments may still prove budget_exceeded=true.';

-- ---------------------------------------------------------------------------
-- 2. Missing organization policy rollout note.
--
-- V62-capable Java fails closed in the application layer when no policy version
-- exists. The DB enforcement is intentionally deferred to V65 so older JVMs
-- can remain alive during the V62/V64 expand phases of a rolling deployment.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 3. Canonical arrays and safe catalog display text.
-- ---------------------------------------------------------------------------
CREATE FUNCTION public.model_capability_array_has_unique_values_v62(keys text[])
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    SELECT cardinality(keys) = (
        SELECT count(DISTINCT item.value)
        FROM unnest(keys) AS item(value)
    )
$$;

ALTER TABLE public.model_route_decisions
    ADD CONSTRAINT chk_model_route_capabilities_unique_v62
    CHECK (public.model_capability_array_has_unique_values_v62(required_capabilities))
    NOT VALID;

ALTER TABLE public.model_route_decisions
    VALIDATE CONSTRAINT chk_model_route_capabilities_unique_v62;

ALTER TABLE public.model_catalog_entries
    ADD CONSTRAINT chk_model_catalog_display_name_controls_v62
    CHECK (display_name !~ '[[:cntrl:]]')
    NOT VALID;

-- Keep immutable historical catalog snapshots readable even if a pre-V62 row
-- contains a control character. PostgreSQL enforces this check for every new
-- catalog version without rewriting old provenance.

-- ---------------------------------------------------------------------------
-- 4. Durable binding of the fully materialized provider request.
--
-- The pre-RAG request is already sealed in model_route_reserved_requests. V62
-- adds a separate append-only receipt for the final request after RAG/context
-- fitting. Recovery may re-materialize a request, but it may not execute it if
-- its final hash differs from the receipt committed before ExecutionPlan/I/O.
-- ---------------------------------------------------------------------------
CREATE TABLE public.model_route_prepared_requests_v62 (
    model_route_decision_id uuid PRIMARY KEY
        REFERENCES public.model_route_decisions(id) ON DELETE RESTRICT,
    chat_turn_id uuid NOT NULL,
    provider_operation_id uuid NOT NULL,
    base_request_sha256 varchar(64) NOT NULL,
    prepared_request_sha256 varchar(64) NOT NULL,
    input_accounting_version varchar(64) NOT NULL,
    prepared_input_units bigint NOT NULL,
    created_at timestamptz NOT NULL,

    CONSTRAINT uq_model_route_prepared_requests_scope_v62
        UNIQUE (model_route_decision_id, chat_turn_id, provider_operation_id),
    CONSTRAINT chk_model_route_prepared_hashes_v62
        CHECK (
            base_request_sha256 ~ '^[0-9a-f]{64}$'
            AND prepared_request_sha256 ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT chk_model_route_prepared_accounting_v62
        CHECK (
            length(btrim(input_accounting_version)) BETWEEN 1 AND 64
            AND input_accounting_version = btrim(input_accounting_version)
            AND input_accounting_version !~ '[[:cntrl:]]'
            AND prepared_input_units >= 0
        )
);

CREATE FUNCTION public.guard_model_route_prepared_request_v62()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_decision public.model_route_decisions%ROWTYPE;
BEGIN
    SELECT * INTO v_decision
    FROM public.model_route_decisions
    WHERE id = NEW.model_route_decision_id;

    IF NOT FOUND
       OR v_decision.outcome IS DISTINCT FROM 'ALLOWED'
       OR v_decision.decision_integrity_version IS DISTINCT FROM 3
       OR v_decision.chat_turn_id IS DISTINCT FROM NEW.chat_turn_id
       OR v_decision.input_accounting_version IS DISTINCT FROM NEW.input_accounting_version
       OR v_decision.estimated_input_tokens IS NULL
       OR NEW.prepared_input_units > v_decision.estimated_input_tokens THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V62 prepared request differs from governed route envelope';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.model_route_reserved_requests r
        WHERE r.model_route_decision_id = NEW.model_route_decision_id
          AND r.chat_turn_id = NEW.chat_turn_id
          AND r.provider_operation_id = NEW.provider_operation_id
          AND r.base_request_sha256 = NEW.base_request_sha256
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'V62 prepared request has no exact sealed pre-RAG reservation';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_model_route_prepared_requests_guard_v62
BEFORE INSERT ON public.model_route_prepared_requests_v62
FOR EACH ROW EXECUTE FUNCTION public.guard_model_route_prepared_request_v62();

CREATE TRIGGER trg_model_route_prepared_requests_immutable_v62
BEFORE UPDATE OR DELETE ON public.model_route_prepared_requests_v62
FOR EACH ROW EXECUTE FUNCTION public.reject_model_control_plane_mutation_v45();

COMMENT ON TABLE public.model_route_prepared_requests_v62 IS
'Append-only SHA-256 receipt of the fully materialized provider request, committed after final context preparation and before execution-plan/provider I/O.';

-- Physical-attempt enforcement is added by V65 after the V64 prepared-request
-- provenance rollout and after all serving JVMs write binding_version=2 receipts.

-- ---------------------------------------------------------------------------
-- 5. V62 monthly cost commitment read model.
--
-- The V60 view conservatively represented physical cost, reservation and
-- unknown liability, but its PROCESSING reservation branch also reserved a
-- hypothetical next call after AMBIGUOUS/non-retryable attempts and after the
-- first attempt of a V59 bounded-cost route. V62 reserves a next call only when
-- the latest attempt explicitly authorizes same-target retry.
-- ---------------------------------------------------------------------------
CREATE VIEW public.model_cost_commitment_ledger_v62 AS
WITH attempt_facts AS (
    SELECT p.organization_id,
           p.model_route_decision_id,
           a.id AS execution_attempt_id,
           a.started_at AS ledger_at,
           a.outcome,
           (a.outcome <> 'STARTED'
             AND a.usage_status = 'AVAILABLE'
             AND a.specialized_dimensions_valid IS TRUE
             AND a.pricing_status IN ('PRICED', 'FREE')
             AND a.cost_usd IS NOT NULL) AS exact,
           a.outcome_certainty,
           a.cost_usd,
           d.estimated_max_cost_usd,
           d.pricing_complete
    FROM public.model_execution_attempts a
    JOIN public.model_execution_plans p
      ON p.id = a.execution_plan_id
    LEFT JOIN public.model_route_decisions d
      ON d.id = p.model_route_decision_id
),
physical AS (
    SELECT organization_id,
           model_route_decision_id,
           execution_attempt_id,
           ledger_at,
           'PHYSICAL_ATTEMPT'::text AS source,
           CASE WHEN exact THEN cost_usd ELSE 0::numeric END AS actual_cost_usd,
           CASE WHEN outcome = 'STARTED' AND pricing_complete
                THEN coalesce(estimated_max_cost_usd, 0)
                ELSE 0::numeric END AS reserved_cost_usd,
           CASE
               WHEN exact
                    OR (outcome = 'FAILED'
                        AND outcome_certainty = 'KNOWN_NOT_EXECUTED')
                    OR (outcome = 'STARTED'
                        AND pricing_complete
                        AND estimated_max_cost_usd IS NOT NULL)
               THEN 0::bigint
               ELSE 1::bigint
           END AS unknown_cost_count,
           outcome = 'STARTED' AS carry_forward
    FROM attempt_facts
),
latest_attempt AS (
    SELECT DISTINCT ON (a.execution_plan_id)
           a.execution_plan_id,
           a.outcome,
           a.outcome_certainty,
           a.retry_safety
    FROM public.model_execution_attempts a
    ORDER BY a.execution_plan_id, a.attempt_number DESC
),
pending AS (
    SELECT d.organization_id,
           d.id AS model_route_decision_id,
           NULL::uuid AS execution_attempt_id,
           d.created_at AS ledger_at,
           'PENDING_RESERVATION'::text AS source,
           0::numeric AS actual_cost_usd,
           CASE WHEN d.pricing_complete
                THEN coalesce(d.estimated_max_cost_usd, 0)
                ELSE 0::numeric END AS reserved_cost_usd,
           CASE WHEN d.pricing_complete
                     AND d.estimated_max_cost_usd IS NOT NULL
                THEN 0::bigint
                ELSE 1::bigint END AS unknown_cost_count,
           true AS carry_forward
    FROM public.model_route_decisions d
    JOIN public.chat_turns t
      ON t.model_route_decision_id = d.id
    LEFT JOIN public.model_execution_plans p
      ON p.model_route_decision_id = d.id
     AND p.chat_turn_id = t.id
    LEFT JOIN latest_attempt la
      ON la.execution_plan_id = p.id
    WHERE d.outcome = 'ALLOWED'
      AND t.state = 'PROCESSING'
      AND (
          p.id IS NULL
          OR la.execution_plan_id IS NULL
          OR (
              la.outcome = 'FAILED'
              AND la.outcome_certainty IN ('KNOWN_NOT_EXECUTED', 'KNOWN_REJECTED')
              AND la.retry_safety = 'SAME_TARGET_RETRY_ALLOWED'
              AND NOT (
                  (d.budget_enforcement = 'HARD'
                   AND d.monthly_budget_usd IS NOT NULL)
                  OR EXISTS (
                      SELECT 1
                      FROM public.organization_model_policies pol
                      WHERE pol.id = d.policy_id
                        AND pol.version = d.policy_version
                        AND pol.organization_id = d.organization_id
                        AND pol.enabled
                        AND pol.max_request_cost_usd IS NOT NULL
                  )
              )
          )
      )
),
missing_evidence AS (
    SELECT d.organization_id,
           d.id AS model_route_decision_id,
           NULL::uuid AS execution_attempt_id,
           d.created_at AS ledger_at,
           'MISSING_PHYSICAL_EVIDENCE'::text AS source,
           0::numeric AS actual_cost_usd,
           0::numeric AS reserved_cost_usd,
           1::bigint AS unknown_cost_count,
           false AS carry_forward
    FROM public.model_route_decisions d
    JOIN public.chat_turns t
      ON t.model_route_decision_id = d.id
    JOIN public.model_execution_plans p
      ON p.model_route_decision_id = d.id
     AND p.chat_turn_id = t.id
    WHERE d.outcome = 'ALLOWED'
      AND (
          t.state IN ('SUCCEEDED', 'AMBIGUOUS')
          OR (t.state = 'FAILED' AND t.provider_call_started_at IS NOT NULL)
      )
      AND NOT EXISTS (
          SELECT 1
          FROM public.model_execution_attempts a
          WHERE a.execution_plan_id = p.id
            AND (
                (t.state = 'SUCCEEDED' AND a.outcome = 'SUCCEEDED')
                OR (t.state = 'AMBIGUOUS'
                    AND a.outcome IN ('STARTED', 'AMBIGUOUS', 'SUCCEEDED'))
                OR (t.state = 'FAILED'
                    AND t.provider_call_started_at IS NOT NULL)
            )
      )
),

legacy AS (
    SELECT d.organization_id,
           d.id AS model_route_decision_id,
           NULL::uuid AS execution_attempt_id,
           d.created_at AS ledger_at,
           'LEGACY_ROUTE_TIMESTAMP'::text AS source,
           CASE WHEN t.state = 'SUCCEEDED'
                     AND m.usage_status = 'AVAILABLE'
                     AND m.pricing_status IN ('PRICED', 'FREE')
                     AND m.cost_usd IS NOT NULL
                THEN m.cost_usd ELSE 0::numeric END AS actual_cost_usd,
           0::numeric AS reserved_cost_usd,
           CASE WHEN t.state = 'SUCCEEDED'
                     AND m.usage_status = 'AVAILABLE'
                     AND m.pricing_status IN ('PRICED', 'FREE')
                     AND m.cost_usd IS NOT NULL
                THEN 0::bigint ELSE 1::bigint END AS unknown_cost_count,
           false AS carry_forward
    FROM public.model_route_decisions d
    JOIN public.chat_turns t
      ON t.model_route_decision_id = d.id
    LEFT JOIN public.chat_messages m
      ON m.id = t.assistant_message_id
    WHERE d.outcome = 'ALLOWED'
      AND t.state IN ('SUCCEEDED', 'FAILED', 'AMBIGUOUS')
      AND NOT EXISTS (
          SELECT 1
          FROM public.model_execution_plans p
          WHERE p.model_route_decision_id = d.id
            AND p.chat_turn_id = t.id
      )
)
SELECT * FROM physical
UNION ALL
SELECT * FROM pending
UNION ALL
SELECT * FROM missing_evidence
UNION ALL
SELECT * FROM legacy;

COMMENT ON VIEW public.model_cost_commitment_ledger_v62 IS
'V62 UTC commitment model: provider-evidenced actual cost, active upper-bound reservations, and unknown liability. A next-attempt reservation exists only before attempt #1 or after an explicitly retry-safe failed attempt; V59 bounded-cost routes never reserve a forbidden retry.';

