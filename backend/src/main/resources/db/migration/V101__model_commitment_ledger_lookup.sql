-- SafeAI-Desk/backend/src/main/resources/db/migration/V101__model_commitment_ledger_lookup.sql
-- Preserve every V62 ledger row, column, owner and grant; only the latest
-- attempt access path changes. V50 guarantees (plan, attempt_number) uniqueness.
-- Reuse that B-tree; do not build another index or rewrite financial history.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = pg_catalog, pg_temp;

-- Fail closed on schema drift, rather than recreate a missing financial view
-- or silently reset a deployment's security-related view options.
DO $guard$
DECLARE
    ledger_oid oid := pg_catalog.to_regclass('public.model_cost_commitment_ledger_v62');
    attempt_oid oid := pg_catalog.to_regclass('public.model_execution_attempts');
    plan_attnum smallint;
    number_attnum smallint;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'V101 requires READ COMMITTED for fresh catalog checks';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_class c
        WHERE c.oid = ledger_oid AND c.relkind = 'v'
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'V101 expected V62 ledger view is missing';
    END IF;

    -- Acquire ordinary read locks without scanning data, and keep them until
    -- the transactional replacement. Concurrent readers can cause a bounded
    -- lock-upgrade failure; never recursively take exclusive locks on tables.
    PERFORM 1 FROM public.model_cost_commitment_ledger_v62 LIMIT 0;

    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_class c
        WHERE c.oid = ledger_oid AND c.relkind = 'v'
          AND coalesce(cardinality(c.reloptions), 0) = 0
          AND c.oid = pg_catalog.to_regclass('public.model_cost_commitment_ledger_v62')
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'V101 unexpected ledger view options or identity';
    END IF;

    IF (SELECT array_agg(a.attname::text ORDER BY a.attnum)
        FROM pg_catalog.pg_attribute a
        WHERE a.attrelid = ledger_oid AND a.attnum > 0 AND NOT a.attisdropped)
        IS DISTINCT FROM ARRAY[
            'organization_id', 'model_route_decision_id', 'execution_attempt_id',
            'ledger_at', 'source', 'actual_cost_usd', 'reserved_cost_usd',
            'unknown_cost_count', 'carry_forward'
        ]::text[]
       OR (SELECT array_agg(a.atttypid ORDER BY a.attnum)
           FROM pg_catalog.pg_attribute a
           WHERE a.attrelid = ledger_oid AND a.attnum > 0 AND NOT a.attisdropped)
           IS DISTINCT FROM ARRAY[
               'uuid'::regtype::oid, 'uuid'::regtype::oid, 'uuid'::regtype::oid,
               'timestamptz'::regtype::oid, 'text'::regtype::oid,
               'numeric'::regtype::oid, 'numeric'::regtype::oid,
               'int8'::regtype::oid, 'bool'::regtype::oid
           ] THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'V101 unexpected ledger column contract';
    END IF;

    -- Stabilize the index catalog against concurrent DROP/REINDEX/CREATE
    -- INDEX CONCURRENTLY. This mode does not conflict with ordinary DML.
    LOCK TABLE public.model_execution_attempts IN SHARE UPDATE EXCLUSIVE MODE;
    IF attempt_oid IS DISTINCT FROM pg_catalog.to_regclass('public.model_execution_attempts') THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'V101 unexpected attempts table identity';
    END IF;

    SELECT a.attnum INTO plan_attnum FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = attempt_oid AND a.attname = 'execution_plan_id'
      AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull
      AND a.atttypid = 'uuid'::regtype;
    SELECT a.attnum INTO number_attnum FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = attempt_oid AND a.attname = 'attempt_number'
      AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull
      AND a.atttypid = 'int4'::regtype;

    IF plan_attnum IS NULL OR number_attnum IS NULL OR NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_constraint c
        JOIN pg_catalog.pg_index i ON i.indexrelid = c.conindid
        JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
        JOIN pg_catalog.pg_am am ON am.oid = idx.relam
        WHERE c.conrelid = attempt_oid AND c.conname = 'uq_model_execution_attempt_number_v50'
          AND c.contype = 'u' AND c.convalidated AND NOT c.condeferrable
          AND c.conkey = ARRAY[plan_attnum, number_attnum]
          AND i.indrelid = attempt_oid AND i.indisunique AND i.indimmediate
          AND i.indisvalid AND i.indisready AND i.indislive
          AND i.indnkeyatts = 2 AND i.indnatts = 2
          AND i.indkey[0] = plan_attnum AND i.indkey[1] = number_attnum
          AND i.indpred IS NULL AND i.indexprs IS NULL
          AND am.amname = 'btree'
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'V101 expected valid V50 unique B-tree is missing or changed';
    END IF;
END;
$guard$;

CREATE OR REPLACE VIEW public.model_cost_commitment_ledger_v62 AS
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
    LEFT JOIN LATERAL (
        SELECT a.execution_plan_id, a.outcome, a.outcome_certainty, a.retry_safety
        FROM public.model_execution_attempts a
        WHERE a.execution_plan_id = p.id
        ORDER BY a.attempt_number DESC
        LIMIT 1
    ) la ON true
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
