/* Safeai-desk/backend/src/main/resources/db/migration/V49__model_control_plane_strict_routing_and_generic_pricing.sql */
/*
 * SafeAI Desk V49 — Model Control Plane strict routing / generic pricing.
 *
 * V45..V48 are immutable and MUST NOT be edited after deployment.
 *
 * This migration:
 *  1) removes provider-specific cache-rate ordering from the generic catalog
 *     database invariant;
 *  2) admits AMBIGUOUS_RUNTIME_MAPPING as immutable DENIED route evidence;
 *  3) prevents new integrity-v3 decisions from using the transitional
 *     LEGACY_RUNTIME_FALLBACK while preserving historical v1/v2 evidence.
 */

-- ---------------------------------------------------------------------------
-- 0. Baseline / drift preflight.
-- ---------------------------------------------------------------------------

do $$
begin
    if to_regclass('public.model_catalog_entries') is null
       or to_regclass('public.model_route_decisions') is null then
        raise exception
            'Cannot apply V49: V45 Model Control Plane tables are missing';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.model_catalog_entries'::regclass
          and conname = 'chk_model_catalog_entries_pricing_semantics'
    ) then
        raise exception
            'Cannot apply V49: expected catalog pricing constraint is missing';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.model_route_decisions'::regclass
          and conname = 'chk_model_route_decisions_reason'
    ) or not exists (
        select 1
        from pg_constraint
        where conrelid = 'public.model_route_decisions'::regclass
          and conname = 'chk_model_route_decisions_reason_semantics'
    ) then
        raise exception
            'Cannot apply V49: expected route reason constraints are missing';
    end if;

    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'model_route_decisions'
          and column_name = 'decision_integrity_version'
    ) then
        raise exception
            'Cannot apply V49: decision_integrity_version is missing';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 1. Generic provider-independent pricing semantics.
--
-- Each known price is independently constrained to be non-negative by
-- chk_model_catalog_entries_prices_non_negative.
--
-- The generic catalog MUST NOT encode assumptions such as:
--   cached_input <= input
--   cache_write_input <= input
--
-- Those relationships are provider/tariff-specific and may legitimately be
-- false for current or future providers.
-- ---------------------------------------------------------------------------

alter table public.model_catalog_entries
    drop constraint chk_model_catalog_entries_pricing_semantics;

alter table public.model_catalog_entries
    add constraint chk_model_catalog_entries_pricing_semantics
        check (
            (
                pricing_status = 'UNPRICED'
                and pricing_complete = false
                and input_usd_per_1m_tokens is null
                and cached_input_usd_per_1m_tokens is null
                and cache_write_input_usd_per_1m_tokens is null
                and output_usd_per_1m_tokens is null
                and extra_pricing_json = '{}'::jsonb
            )
            or
            (
                pricing_status = 'FREE'
                and pricing_complete = true
                and input_usd_per_1m_tokens = 0
                and output_usd_per_1m_tokens = 0
                and coalesce(cached_input_usd_per_1m_tokens, 0) = 0
                and coalesce(cache_write_input_usd_per_1m_tokens, 0) = 0
                and extra_pricing_json = '{}'::jsonb
            )
            or
            (
                pricing_status = 'CONFIGURED'
                and pricing_complete = true
                and input_usd_per_1m_tokens is not null
                and output_usd_per_1m_tokens is not null
                and pricing_version is not null
                and length(btrim(pricing_version)) > 0
                and extra_pricing_json = '{}'::jsonb
            )
            or
            (
                pricing_status = 'INCOMPLETE'
                and pricing_complete = false
            )
        )
        not valid;

alter table public.model_catalog_entries
    validate constraint chk_model_catalog_entries_pricing_semantics;

-- ---------------------------------------------------------------------------
-- 2. Route reason evolution.
--
-- LEGACY_RUNTIME_FALLBACK remains listed because immutable historical v1/v2
-- rows may contain it. New v3 evidence is prohibited from using it below.
-- ---------------------------------------------------------------------------

alter table public.model_route_decisions
    drop constraint chk_model_route_decisions_reason;

alter table public.model_route_decisions
    add constraint chk_model_route_decisions_reason
        check (reason in (
            'REQUESTED_MODEL',
            'POLICY_DEFAULT',
            'RUNTIME_ONLY_MATCH',
            'LEGACY_RUNTIME_FALLBACK',
            'MODEL_NOT_ALLOWED',
            'MODEL_DENIED',
            'MODEL_NOT_FOUND',
            'AMBIGUOUS_RUNTIME_MAPPING',
            'MODEL_DISABLED',
            'RUNTIME_MISMATCH',
            'CAPABILITY_UNSUPPORTED',
            'INPUT_LIMIT_EXCEEDED',
            'OUTPUT_LIMIT_EXCEEDED',
            'PRICING_INCOMPLETE',
            'TRAINING_POLICY_UNSATISFIED',
            'RETENTION_POLICY_UNSATISFIED',
            'REQUEST_COST_LIMIT_EXCEEDED',
            'MONTHLY_BUDGET_EXCEEDED',
            'MONTHLY_BUDGET_UNVERIFIABLE'
        ))
        not valid;

alter table public.model_route_decisions
    validate constraint chk_model_route_decisions_reason;

alter table public.model_route_decisions
    drop constraint chk_model_route_decisions_reason_semantics;

alter table public.model_route_decisions
    add constraint chk_model_route_decisions_reason_semantics
        check (
            (
                outcome = 'ALLOWED'
                and reason in (
                    'REQUESTED_MODEL',
                    'POLICY_DEFAULT',
                    'RUNTIME_ONLY_MATCH',
                    'LEGACY_RUNTIME_FALLBACK'
                )
            )
            or
            (
                outcome = 'DENIED'
                and reason in (
                    'MODEL_NOT_ALLOWED',
                    'MODEL_DENIED',
                    'MODEL_NOT_FOUND',
                    'AMBIGUOUS_RUNTIME_MAPPING',
                    'MODEL_DISABLED',
                    'RUNTIME_MISMATCH',
                    'CAPABILITY_UNSUPPORTED',
                    'INPUT_LIMIT_EXCEEDED',
                    'OUTPUT_LIMIT_EXCEEDED',
                    'PRICING_INCOMPLETE',
                    'TRAINING_POLICY_UNSATISFIED',
                    'RETENTION_POLICY_UNSATISFIED',
                    'REQUEST_COST_LIMIT_EXCEEDED',
                    'MONTHLY_BUDGET_EXCEEDED',
                    'MONTHLY_BUDGET_UNVERIFIABLE'
                )
            )
        )
        not valid;

alter table public.model_route_decisions
    validate constraint chk_model_route_decisions_reason_semantics;

-- ---------------------------------------------------------------------------
-- 3. Strict no-legacy rule for new V3 decisions.
--
-- Existing immutable v1/v2 rows remain readable/verifiable.
-- Current Java writes integrity v3, so any accidental reintroduction of
-- LEGACY_RUNTIME_FALLBACK fails closed in PostgreSQL as well.
-- ---------------------------------------------------------------------------

alter table public.model_route_decisions
    add constraint chk_model_route_decisions_no_legacy_fallback_v49
        check (
            decision_integrity_version < 3
            or reason <> 'LEGACY_RUNTIME_FALLBACK'
        )
        not valid;

alter table public.model_route_decisions
    validate constraint chk_model_route_decisions_no_legacy_fallback_v49;

comment on constraint chk_model_catalog_entries_pricing_semantics
    on public.model_catalog_entries is
    'Generic catalog pricing-state semantics only; no provider-specific ordering between input/cache rates.';

comment on constraint chk_model_route_decisions_no_legacy_fallback_v49
    on public.model_route_decisions is
    'Preserves historical v1/v2 legacy evidence while prohibiting LEGACY_RUNTIME_FALLBACK for new integrity-v3 decisions.';
