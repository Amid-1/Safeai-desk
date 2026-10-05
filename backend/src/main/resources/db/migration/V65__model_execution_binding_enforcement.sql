-- backend/src/main/resources/db/migration/V65__model_execution_binding_enforcement.sql
-- SafeAI Desk V65 — Model Control Plane post-rollout execution-binding enforcement.
--
-- V63 is Knowledge publication/retention hardening.
-- V64 is Model Control Plane completion hardening.
-- V65 moves the former conflicting model V63 migration forward and completes
-- the post-rollout contract with v2-only physical-execution enforcement.
-- V1-V64 are immutable after deployment.
--
-- Apply only after all serving JVMs run V64-capable code and write
-- binding_version=2 prepared-request receipts. Historical binding_version=1
-- rows remain readable, but cannot authorize any new physical provider I/O.
--
-- IMPORTANT FOR IDEs:
-- IntelliJ may inspect this migration against a datasource that has not applied
-- V64/V65 yet. Objects introduced by those migrations are therefore referenced
-- through PostgreSQL runtime lookup / dynamic DDL where required.
--
-- This does NOT weaken database enforcement:
--   * V64 baseline objects are explicitly preflighted;
--   * every new prepared-request receipt is constrained to binding_version=2;
--   * every physical execution attempt is guarded by a BEFORE INSERT trigger;
--   * historical binding_version=1 evidence remains readable only.

DO $v65_preflight$
DECLARE
    v_binding_type text;
    v_binding_nullable text;
    v_has_unbound_processing boolean;
BEGIN
    -- -----------------------------------------------------------------------
    -- 1. V64 baseline preflight.
    -- -----------------------------------------------------------------------
    IF to_regclass('public.model_route_prepared_requests_v62') IS NULL
       OR to_regclass('public.model_route_decisions') IS NULL
       OR to_regclass('public.model_execution_plans') IS NULL
       OR to_regclass('public.model_execution_attempts') IS NULL
       OR to_regclass('public.chat_turns') IS NULL
       OR to_regclass('public.knowledge_rag_context_receipts_v64') IS NULL THEN
        RAISE EXCEPTION
            'Cannot apply V65: V64 execution-binding baseline is missing';
    END IF;

    SELECT c.data_type,
           c.is_nullable
    INTO v_binding_type,
         v_binding_nullable
    FROM information_schema.columns c
    WHERE c.table_schema = 'public'
      AND c.table_name = 'model_route_prepared_requests_v62'
      AND c.column_name = 'binding_version';

    IF NOT FOUND
       OR v_binding_type IS DISTINCT FROM 'smallint'
       OR v_binding_nullable IS DISTINCT FROM 'NO'
       OR to_regprocedure('public.guard_model_route_prepared_request_v64()') IS NULL THEN
        RAISE EXCEPTION
            'Cannot apply V65: V64 prepared-request provenance contract is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_trigger trigger_row
        WHERE trigger_row.tgrelid =
                  to_regclass('public.model_route_prepared_requests_v62')
          AND trigger_row.tgname =
                  'trg_model_route_prepared_requests_guard_v64'
          AND NOT trigger_row.tgisinternal
    ) THEN
        RAISE EXCEPTION
            'Cannot apply V65: V64 prepared-request guard trigger is missing';
    END IF;

    -- -----------------------------------------------------------------------
    -- 2. Drain all pre-V64/in-flight executions before enabling the strict
    --    v2-only physical-I/O fence.
    --
    -- The V64-owned prepared-request table/column are referenced inside a
    -- runtime statement only after the preflight above. This keeps the
    -- migration fail-closed while avoiding stale-datasource SqlResolve noise.
    -- -----------------------------------------------------------------------
    EXECUTE format(
        $sql$
        SELECT EXISTS (
            SELECT 1
            FROM public.model_execution_plans p
            JOIN public.chat_turns t
              ON t.id = p.chat_turn_id
            WHERE t.state = 'PROCESSING'
              AND NOT EXISTS (
                  SELECT 1
                  FROM public.model_route_prepared_requests_v62 b
                  WHERE b.model_route_decision_id = p.model_route_decision_id
                    AND b.chat_turn_id = p.chat_turn_id
                    AND b.provider_operation_id = p.provider_operation_id
                    AND b.%I = 2
              )
        )
        $sql$,
        'binding_version'
    )
    INTO v_has_unbound_processing;

    IF v_has_unbound_processing THEN
        RAISE EXCEPTION
            'V65 preflight: active execution plan without V64 binding_version=2 final-request receipt; drain/recover pre-V64 work first';
    END IF;
END
$v65_preflight$;

-- ---------------------------------------------------------------------------
-- 3. End rolling compatibility for NEW prepared-request receipts.
--
-- V64 intentionally used DEFAULT 1 so old and new JVMs could coexist during a
-- rolling deployment. V65 is the post-rollout cutover:
--   * historical binding_version=1 rows remain stored/readable;
--   * every new prepared-request receipt must be binding_version=2;
--   * physical provider I/O is authorized only by binding_version=2.
--
-- All DDL touching the V64/V65-only objects stays inside dynamic SQL so an
-- IntelliJ datasource that has not yet applied the migrations does not produce
-- false unresolved table/constraint diagnostics.
-- ---------------------------------------------------------------------------
DO $v65_binding_contract$
BEGIN
    EXECUTE format(
        'ALTER TABLE %I.%I ALTER COLUMN %I SET DEFAULT 2',
        'public',
        'model_route_prepared_requests_v62',
        'binding_version'
    );

    EXECUTE format(
        'ALTER TABLE %I.%I '
        || 'ADD CONSTRAINT %I CHECK (%I = 2) NOT VALID',
        'public',
        'model_route_prepared_requests_v62',
        'chk_model_route_prepared_current_binding_v65',
        'binding_version'
    );

    EXECUTE format(
        'COMMENT ON COLUMN %I.%I.%I IS %L',
        'public',
        'model_route_prepared_requests_v62',
        'binding_version',
        '1 = historical V62/V64 rolling-deployment binding; 2 = current V64 provenance-aware binding required for all new receipts and all new physical provider I/O after V65.'
    );

    EXECUTE format(
        'COMMENT ON CONSTRAINT %I ON %I.%I IS %L',
        'chk_model_route_prepared_current_binding_v65',
        'public',
        'model_route_prepared_requests_v62',
        'Post-rollout fence: historical binding_version=1 rows remain readable, but every new prepared-request receipt must use V64 provenance-aware binding_version=2.'
    );
END
$v65_binding_contract$;

-- ---------------------------------------------------------------------------
-- 4. New integrity-v3 route evidence cannot execute without tenant policy.
--
-- Historical rows are intentionally preserved by NOT VALID. PostgreSQL still
-- enforces this CHECK for every row inserted/updated after this migration.
-- The only policy-less v3 shape retained is the explicit fail-closed
-- unconfigured-policy DENIED/MODEL_NOT_ALLOWED evidence row.
-- ---------------------------------------------------------------------------
ALTER TABLE public.model_route_decisions
    ADD CONSTRAINT chk_model_route_decisions_v3_policy_binding_v65
    CHECK (
        decision_integrity_version < 3
        OR policy_id IS NOT NULL
        OR (
            outcome = 'DENIED'
            AND reason = 'MODEL_NOT_ALLOWED'
            AND policy_version IS NULL
            AND selected_catalog_entry_id IS NULL
            AND selected_catalog_version IS NULL
            AND selected_model_key IS NULL
            AND selected_provider IS NULL
            AND selected_provider_model_id IS NULL
        )
    ) NOT VALID;

COMMENT ON CONSTRAINT chk_model_route_decisions_v3_policy_binding_v65
ON public.model_route_decisions IS
'New strict v3 route evidence cannot execute without an organization policy. policy_id=NULL + DENIED/MODEL_NOT_ALLOWED is the fail-closed unconfigured state; historical evidence remains unchanged.';

-- ---------------------------------------------------------------------------
-- 5. Physical execution fence.
--
-- Every INSERT into model_execution_attempts must be transitively bound through
-- its execution plan to one exact append-only V64 prepared-request receipt with
-- binding_version=2. This is the database backstop behind the application-level
-- execution identity / prepared-request checks.
--
-- Function, trigger and function comment are emitted from one dynamic DDL
-- block. This avoids IntelliJ resolving a function that does not exist until
-- this very migration runs, while PostgreSQL still creates a normal persistent
-- PL/pgSQL trigger function and a normal BEFORE INSERT trigger.
-- ---------------------------------------------------------------------------
DO $v65_execution_fence$
BEGIN
    EXECUTE format(
        $ddl$
        CREATE FUNCTION %I.%I()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $fn$
        DECLARE
            v_plan public.model_execution_plans%%ROWTYPE;
        BEGIN
            SELECT *
            INTO v_plan
            FROM public.model_execution_plans
            WHERE id = NEW.execution_plan_id;

            IF NOT FOUND THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'V65 execution attempt has no execution plan';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                FROM public.model_route_prepared_requests_v62 b
                WHERE b.model_route_decision_id = v_plan.model_route_decision_id
                  AND b.chat_turn_id = v_plan.chat_turn_id
                  AND b.provider_operation_id = v_plan.provider_operation_id
                  AND b.%I = 2
            ) THEN
                RAISE EXCEPTION USING
                    ERRCODE = '23514',
                    MESSAGE = 'V65 physical attempt has no current binding_version=2 durable final-request binding';
            END IF;

            RETURN NEW;
        END
        $fn$
        $ddl$,
        'public',
        'guard_execution_attempt_prepared_binding_v65',
        'binding_version'
    );

    EXECUTE format(
        'CREATE TRIGGER %I '
        || 'BEFORE INSERT ON %I.%I '
        || 'FOR EACH ROW '
        || 'EXECUTE FUNCTION %I.%I()',
        'trg_execution_attempt_prepared_binding_v65',
        'public',
        'model_execution_attempts',
        'public',
        'guard_execution_attempt_prepared_binding_v65'
    );

    EXECUTE format(
        'COMMENT ON FUNCTION %I.%I() IS %L',
        'public',
        'guard_execution_attempt_prepared_binding_v65',
        'DB backstop: every new physical attempt must be transitively bound to a V64 provenance-aware binding_version=2 append-only final materialized request receipt.'
    );
END
$v65_execution_fence$;
