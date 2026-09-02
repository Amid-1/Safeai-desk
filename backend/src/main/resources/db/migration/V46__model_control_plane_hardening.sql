/* Safeai-desk/backend/src/main/resources/db/migration/V46__model_control_plane_hardening.sql */
/* SafeAI Desk V46 — Model Control Plane correctness hardening.
 *
 * - adds REQUESTED_MODEL provenance;
 * - introduces integrity format v2 while keeping V45 v1 rows verifiable;
 * - makes MODEL_NOT_FOUND v2 evidence physically unselected;
 * - prevents control characters in new provider/model identifiers.
 *
 * V45 is intentionally not edited.
 */

-- ---------------------------------------------------------------------------
-- 0. Baseline preflight.
-- ---------------------------------------------------------------------------

do $$
begin
    if to_regclass('public.model_route_decisions') is null
       or to_regclass('public.model_catalog_entries') is null
       or to_regclass('public.chat_messages') is null then
        raise exception 'Cannot apply V46: required V45/V17 tables are missing';
    end if;

    if not exists (
        select 1 from pg_attribute
        where attrelid = 'public.chat_messages'::regclass
          and attname = 'usage_status' and not attisdropped
    ) or not exists (
        select 1 from pg_attribute
        where attrelid = 'public.chat_messages'::regclass
          and attname = 'pricing_status' and not attisdropped
    ) then
        raise exception 'Cannot apply V46: V17 usage/pricing metadata is missing';
    end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 1. Explicit digest format version. Existing V45 rows are v1.
-- Rolling deployment is safe: pre-V46 Java omits the column and receives v1.
-- ---------------------------------------------------------------------------

alter table public.model_route_decisions
    add column decision_integrity_version smallint not null default 1;

alter table public.model_route_decisions
    add constraint chk_model_route_decisions_integrity_version_v46
        check (decision_integrity_version in (1, 2));

comment on column public.model_route_decisions.decision_integrity_version is
    '1 = legacy V45 line canonicalization; 2 = unambiguous length-prefixed canonical encoding.';

-- ---------------------------------------------------------------------------
-- 2. Route reason evolution. REQUESTED_MODEL is an ALLOWED reason.
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
        ));

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
        );

-- ---------------------------------------------------------------------------
-- 3. V2 provenance semantics: MODEL_NOT_FOUND means no physical target was
-- selected. Old v1 evidence is preserved as-is for backward compatibility.
-- ---------------------------------------------------------------------------

alter table public.model_route_decisions
    add constraint chk_model_route_decisions_not_found_provenance_v46
        check (
            decision_integrity_version < 2
            or reason <> 'MODEL_NOT_FOUND'
            or (
                selected_catalog_entry_id is null
                and selected_catalog_version is null
                and selected_provider is null
                and selected_provider_model_id is null
            )
        );

-- ---------------------------------------------------------------------------
-- 4. Harden new identifier rows against line/control-character ambiguity.
-- NOT VALID intentionally avoids rewriting/rejecting historical immutable rows;
-- PostgreSQL still enforces these checks for all new rows.
-- ---------------------------------------------------------------------------

alter table public.model_catalog_entries
    add constraint chk_model_catalog_entries_identifiers_no_control_v46
        check (
            provider !~ '[[:cntrl:]]'
            and provider_model_id !~ '[[:cntrl:]]'
        ) not valid;

alter table public.model_route_decisions
    add constraint chk_model_route_decisions_identifiers_no_control_v46
        check (
            decision_integrity_version < 2
            or (
                (selected_provider is null or selected_provider !~ '[[:cntrl:]]')
                and (
                    selected_provider_model_id is null
                    or selected_provider_model_id !~ '[[:cntrl:]]'
                )
            )
        ) not valid;
