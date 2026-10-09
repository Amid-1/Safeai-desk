-- backend/src/main/resources/db/migration/V66__physical_response_and_pricing_scheme.sql
-- SafeAI Desk V66
-- Physical-response evidence and fail-closed pricing-scheme provenance.
--
-- V1-V65 are immutable. Do not edit already applied migrations.

DO $migration$
BEGIN
    IF to_regclass('public.model_execution_attempts') IS NULL
       OR to_regclass('public.model_catalog_entries') IS NULL THEN
        RAISE EXCEPTION
            'V66 requires model execution attempts and model catalog';
    END IF;
END
$migration$;

-- ---------------------------------------------------------------------------
-- 1. A provider response may be physically observed even when application
--    protocol/content validation rejects it.
-- ---------------------------------------------------------------------------

ALTER TABLE public.model_execution_attempts
    ADD COLUMN response_observed boolean NOT NULL DEFAULT false,
    ADD COLUMN response_validation_status varchar(40),
    ADD COLUMN provider_http_status integer,
    ADD COLUMN protocol_failure_code varchar(64);

-- Historical SUCCEEDED rows are the only rows for which V1-V65 already prove
-- that a usable provider response was observed.
--
-- V51 forbids *all* updates to terminal attempts. The V66 transition needs a
-- narrowly scoped, one-time metadata backfill. This block holds an ACCESS
-- EXCLUSIVE lock, temporarily suspends ONLY the V51 terminal-immutability
-- trigger, restores it before the transaction commits, and verifies the exact
-- backfill. Other constraints/triggers remain enabled. If anything fails,
-- PostgreSQL rolls back BOTH the backfill and the trigger DDL atomically.
-- Never use this technique for runtime writes or subsequent migrations.
LOCK TABLE public.model_execution_attempts IN ACCESS EXCLUSIVE MODE;

DO $v66_historical_response_backfill$
DECLARE
    immutability_trigger_name name;
    matching_trigger_count integer;
    expected_succeeded bigint;
    updated_succeeded bigint;
BEGIN
    SELECT count(*), min(t.tgname::text)
      INTO matching_trigger_count, immutability_trigger_name
      FROM pg_trigger t
      JOIN pg_class c ON c.oid = t.tgrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_proc p ON p.oid = t.tgfoid
      JOIN pg_namespace pn ON pn.oid = p.pronamespace
     WHERE n.nspname = 'public'
       AND c.relname = 'model_execution_attempts'
       AND pn.nspname = 'public'
       AND p.proname = 'enforce_model_execution_attempt_immutability_v50'
       AND NOT t.tgisinternal
       AND t.tgenabled = 'O';

    IF matching_trigger_count <> 1 THEN
        RAISE EXCEPTION
            'V66 expected exactly one active V51 immutability trigger, found %',
            matching_trigger_count;
    END IF;

    SELECT count(*) INTO expected_succeeded
      FROM public.model_execution_attempts
     WHERE outcome = 'SUCCEEDED';

    EXECUTE format(
        'ALTER TABLE public.model_execution_attempts DISABLE TRIGGER %I',
        immutability_trigger_name
    );

    -- All other evidence columns remain byte-for-byte unchanged.
    UPDATE public.model_execution_attempts
       SET response_observed = true,
           response_validation_status = 'ACCEPTED'
     WHERE outcome = 'SUCCEEDED';
    GET DIAGNOSTICS updated_succeeded = ROW_COUNT;

    EXECUTE format(
        'ALTER TABLE public.model_execution_attempts ENABLE TRIGGER %I',
        immutability_trigger_name
    );

    IF updated_succeeded <> expected_succeeded THEN
        RAISE EXCEPTION
            'V66 response backfill count mismatch: expected %, updated %',
            expected_succeeded, updated_succeeded;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.model_execution_attempts
         WHERE outcome = 'SUCCEEDED'
           AND (response_observed IS DISTINCT FROM true
             OR response_validation_status IS DISTINCT FROM 'ACCEPTED')
    ) THEN
        RAISE EXCEPTION 'V66 historical response provenance backfill incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_trigger t
          JOIN pg_class c ON c.oid = t.tgrelid
          JOIN pg_proc p ON p.oid = t.tgfoid
         WHERE c.oid = 'public.model_execution_attempts'::regclass
           AND t.tgname = immutability_trigger_name
           AND p.proname = 'enforce_model_execution_attempt_immutability_v50'
           AND t.tgenabled = 'O'
    ) THEN
        RAISE EXCEPTION 'V66 V51 immutability trigger was not restored';
    END IF;
END;
$v66_historical_response_backfill$;

ALTER TABLE public.model_execution_attempts
    ADD CONSTRAINT chk_model_execution_attempt_http_status_v66
    CHECK (
        provider_http_status IS NULL
        OR provider_http_status BETWEEN 100 AND 599
    ) NOT VALID;

ALTER TABLE public.model_execution_attempts
    VALIDATE CONSTRAINT chk_model_execution_attempt_http_status_v66;

ALTER TABLE public.model_execution_attempts
    ADD CONSTRAINT chk_model_execution_attempt_response_status_v66
    CHECK (
        response_validation_status IS NULL
        OR response_validation_status IN (
            'ACCEPTED',
            'PROTOCOL_REJECTED',
            'UNSUPPORTED_TOOL_USE',
            'INVALID_SCHEMA'
        )
    ) NOT VALID;

ALTER TABLE public.model_execution_attempts
    VALIDATE CONSTRAINT chk_model_execution_attempt_response_status_v66;

ALTER TABLE public.model_execution_attempts
    ADD CONSTRAINT chk_model_execution_attempt_protocol_failure_code_v66
    CHECK (
        protocol_failure_code IS NULL
        OR (
            protocol_failure_code = btrim(protocol_failure_code)
            AND length(protocol_failure_code) BETWEEN 1 AND 64
            AND protocol_failure_code !~ '[[:cntrl:]]'
        )
    ) NOT VALID;

ALTER TABLE public.model_execution_attempts
    VALIDATE CONSTRAINT chk_model_execution_attempt_protocol_failure_code_v66;

ALTER TABLE public.model_execution_attempts
    DROP CONSTRAINT chk_model_execution_attempt_enums_v50;

ALTER TABLE public.model_execution_attempts
    ADD CONSTRAINT chk_model_execution_attempt_enums_v66
    CHECK (
        outcome IN (
            'STARTED',
            'SUCCEEDED',
            'FAILED',
            'AMBIGUOUS',
            'PROTOCOL_REJECTED'
        )
        AND outcome_certainty IN (
            'KNOWN_NOT_EXECUTED',
            'KNOWN_REJECTED',
            'KNOWN_EXECUTED',
            'AMBIGUOUS'
        )
        AND retry_safety IN (
            'SAME_TARGET_RETRY_ALLOWED',
            'SAME_TARGET_RETRY_FORBIDDEN'
        )
        AND fallback_safety IN (
            'FALLBACK_ALLOWED',
            'FALLBACK_FORBIDDEN'
        )
        AND (
            usage_status IS NULL
            OR usage_status IN (
                'AVAILABLE',
                'PARTIAL',
                'MISSING'
            )
        )
        AND (
            pricing_status IS NULL
            OR pricing_status IN (
                'PRICED',
                'FREE',
                'UNPRICED',
                'CALCULATION_FAILED'
            )
        )
    ) NOT VALID;

ALTER TABLE public.model_execution_attempts
    VALIDATE CONSTRAINT chk_model_execution_attempt_enums_v66;

ALTER TABLE public.model_execution_attempts
    DROP CONSTRAINT chk_model_execution_attempt_lifecycle_v50;

ALTER TABLE public.model_execution_attempts
    ADD CONSTRAINT chk_model_execution_attempt_lifecycle_v66
    CHECK (
        (
            outcome = 'STARTED'
            AND finished_at IS NULL
            AND resolved_physical_model IS NULL
            AND provider_request_id IS NULL
            AND provider_message_id IS NULL
            AND failure_type IS NULL
            AND usage_status IS NULL
            AND pricing_status IS NULL
            AND outcome_certainty = 'AMBIGUOUS'
            AND retry_safety = 'SAME_TARGET_RETRY_FORBIDDEN'
            AND fallback_safety = 'FALLBACK_FORBIDDEN'
            AND response_observed = false
            AND response_validation_status IS NULL
            AND provider_http_status IS NULL
            AND protocol_failure_code IS NULL
        )
        OR
        (
            outcome = 'SUCCEEDED'
            AND finished_at IS NOT NULL
            AND resolved_physical_model IS NOT NULL
            AND failure_type IS NULL
            AND outcome_certainty = 'KNOWN_EXECUTED'
            AND retry_safety = 'SAME_TARGET_RETRY_FORBIDDEN'
            AND fallback_safety = 'FALLBACK_FORBIDDEN'
            AND usage_status IS NOT NULL
            AND pricing_status IS NOT NULL
            AND response_observed = true
            AND response_validation_status = 'ACCEPTED'
            AND protocol_failure_code IS NULL
        )
        OR
        (
            outcome = 'PROTOCOL_REJECTED'
            AND finished_at IS NOT NULL
            AND failure_type IS NOT NULL
            AND outcome_certainty = 'KNOWN_EXECUTED'
            AND retry_safety = 'SAME_TARGET_RETRY_FORBIDDEN'
            AND fallback_safety = 'FALLBACK_FORBIDDEN'
            AND response_observed = true
            AND response_validation_status IN (
                'PROTOCOL_REJECTED',
                'UNSUPPORTED_TOOL_USE',
                'INVALID_SCHEMA'
            )
            AND protocol_failure_code IS NOT NULL
        )
        OR
        (
            outcome = 'FAILED'
            AND finished_at IS NOT NULL
            AND resolved_physical_model IS NULL
            AND failure_type IS NOT NULL
            AND outcome_certainty IN (
                'KNOWN_NOT_EXECUTED',
                'KNOWN_REJECTED'
            )
            AND response_validation_status IS NULL
        )
        OR
        (
            outcome = 'AMBIGUOUS'
            AND finished_at IS NOT NULL
            AND resolved_physical_model IS NULL
            AND outcome_certainty = 'AMBIGUOUS'
            AND retry_safety = 'SAME_TARGET_RETRY_FORBIDDEN'
            AND fallback_safety = 'FALLBACK_FORBIDDEN'
            AND response_validation_status IS NULL
        )
    ) NOT VALID;

ALTER TABLE public.model_execution_attempts
    VALIDATE CONSTRAINT chk_model_execution_attempt_lifecycle_v66;

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
        WHEN a.outcome = 'STARTED'
            THEN 'PHYSICAL_STARTED_UNRESOLVED'
        WHEN a.outcome = 'AMBIGUOUS'
            THEN 'PHYSICAL_OUTCOME_UNKNOWN'
        WHEN a.outcome = 'PROTOCOL_REJECTED'
             AND a.pricing_status IS DISTINCT FROM 'PRICED'
             AND a.pricing_status IS DISTINCT FROM 'FREE'
            THEN 'PROVIDER_RESPONSE_OBSERVED_COST_UNKNOWN'
        WHEN a.outcome = 'PROTOCOL_REJECTED'
            THEN 'PROVIDER_RESPONSE_PROTOCOL_REJECTED'
        WHEN a.outcome = 'SUCCEEDED' AND t.state <> 'SUCCEEDED'
            THEN 'PROVIDER_SUCCEEDED_CHAT_NOT_SUCCEEDED'
        WHEN a.outcome = 'SUCCEEDED'
             AND a.pricing_status IN ('UNPRICED', 'CALCULATION_FAILED')
            THEN 'PROVIDER_SUCCEEDED_COST_UNKNOWN'
        WHEN a.outcome = 'FAILED'
             AND a.outcome_certainty IS DISTINCT FROM 'KNOWN_NOT_EXECUTED'
             AND a.pricing_status IS DISTINCT FROM 'PRICED'
             AND a.pricing_status IS DISTINCT FROM 'FREE'
            THEN 'FAILED_CALL_COST_NOT_PROVEN'
        ELSE 'NONE'
    END AS reconciliation_reason
FROM public.model_execution_attempts a
JOIN public.model_execution_plans p
  ON p.id = a.execution_plan_id
JOIN public.chat_turns t
  ON t.id = p.chat_turn_id
WHERE a.outcome IN ('STARTED', 'AMBIGUOUS', 'PROTOCOL_REJECTED')
   OR (
       a.outcome = 'SUCCEEDED'
       AND (
           t.state <> 'SUCCEEDED'
           OR a.pricing_status IN ('UNPRICED', 'CALCULATION_FAILED')
       )
   )
   OR (
       a.outcome = 'FAILED'
       AND a.outcome_certainty IS DISTINCT FROM 'KNOWN_NOT_EXECUTED'
       AND a.pricing_status IS DISTINCT FROM 'PRICED'
       AND a.pricing_status IS DISTINCT FROM 'FREE'
   );

COMMENT ON VIEW public.model_execution_reconciliation_queue_v54 IS
'Read-only physical execution incidents including V66 protocol-rejected observed responses; never auto-resubmit a ChatTurn.';

-- ---------------------------------------------------------------------------
-- 2. Explicit pricing scheme.
-- ---------------------------------------------------------------------------

ALTER TABLE public.model_catalog_entries
    ADD COLUMN pricing_scheme varchar(40);

-- Historical catalog snapshots are immutable under V45. Populate only the
-- newly introduced V66 field while holding an exclusive table lock. The exact
-- V45 immutability trigger is restored before this transaction can commit.
-- If an assertion fails, PostgreSQL rolls back all schema/data/trigger changes.
LOCK TABLE public.model_catalog_entries IN ACCESS EXCLUSIVE MODE;

DO $v66_historical_catalog_backfill$
DECLARE
    immutability_trigger_name name;
    matching_trigger_count integer;
    expected_rows bigint;
    updated_rows bigint;
BEGIN
    SELECT count(*), min(t.tgname::text)
      INTO matching_trigger_count, immutability_trigger_name
      FROM pg_trigger t
      JOIN pg_proc p ON p.oid = t.tgfoid
      JOIN pg_namespace pn ON pn.oid = p.pronamespace
     WHERE t.tgrelid = 'public.model_catalog_entries'::regclass
       AND pn.nspname = 'public'
       AND p.proname = 'reject_model_control_plane_mutation_v45'
       AND NOT t.tgisinternal
       AND t.tgenabled = 'O';

    IF matching_trigger_count <> 1 THEN
        RAISE EXCEPTION
            'V66 expected exactly one active V45 catalog immutability trigger, found %',
            matching_trigger_count;
    END IF;

    SELECT count(*) INTO expected_rows
      FROM public.model_catalog_entries
     WHERE pricing_scheme IS NULL;

    EXECUTE format(
        'ALTER TABLE public.model_catalog_entries DISABLE TRIGGER %I',
        immutability_trigger_name
    );

    UPDATE public.model_catalog_entries
       SET pricing_scheme = CASE
           WHEN pricing_status = 'FREE'
               THEN 'FLAT_TOKEN_V1'
           WHEN pricing_status = 'CONFIGURED'
               THEN 'LEGACY_FLAT_UNVERIFIED_V1'
           ELSE 'UNSUPPORTED_COMPLEX_V1'
       END
     WHERE pricing_scheme IS NULL;
    GET DIAGNOSTICS updated_rows = ROW_COUNT;

    EXECUTE format(
        'ALTER TABLE public.model_catalog_entries ENABLE TRIGGER %I',
        immutability_trigger_name
    );

    IF updated_rows <> expected_rows THEN
        RAISE EXCEPTION
            'V66 catalog pricing backfill count mismatch: expected %, updated %',
            expected_rows, updated_rows;
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.model_catalog_entries
         WHERE pricing_scheme IS NULL
            OR pricing_scheme IS DISTINCT FROM CASE
                WHEN pricing_status = 'FREE' THEN 'FLAT_TOKEN_V1'
                WHEN pricing_status = 'CONFIGURED' THEN 'LEGACY_FLAT_UNVERIFIED_V1'
                ELSE 'UNSUPPORTED_COMPLEX_V1'
            END
    ) THEN
        RAISE EXCEPTION 'V66 historical catalog pricing provenance backfill incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_trigger t
          JOIN pg_proc p ON p.oid = t.tgfoid
         WHERE t.tgrelid = 'public.model_catalog_entries'::regclass
           AND t.tgname = immutability_trigger_name
           AND p.proname = 'reject_model_control_plane_mutation_v45'
           AND t.tgenabled = 'O'
    ) THEN
        RAISE EXCEPTION 'V66 V45 catalog immutability trigger was not restored';
    END IF;
END;
$v66_historical_catalog_backfill$;

ALTER TABLE public.model_catalog_entries
    ALTER COLUMN pricing_scheme SET NOT NULL;

ALTER TABLE public.model_catalog_entries
    ADD CONSTRAINT chk_model_catalog_pricing_scheme_v66
    CHECK (
        pricing_scheme IN (
            'FLAT_TOKEN_V1',
            'LEGACY_FLAT_UNVERIFIED_V1',
            'UNSUPPORTED_COMPLEX_V1'
        )
    ) NOT VALID;

ALTER TABLE public.model_catalog_entries
    VALIDATE CONSTRAINT chk_model_catalog_pricing_scheme_v66;

COMMENT ON COLUMN public.model_catalog_entries.pricing_scheme IS
'Settlement algorithm identity. V66 fail-closes legacy CONFIGURED prices until an audited catalog version explicitly selects a supported pricing scheme.';
