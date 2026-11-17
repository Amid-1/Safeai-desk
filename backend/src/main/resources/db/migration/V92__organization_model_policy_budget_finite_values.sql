-- SafeAI-Desk/backend/src/main/resources/db/migration/V92__organization_model_policy_budget_finite_values.sql
-- Additive hardening of immutable V45/V91 policy evidence.
-- Existing rows are validated; invalid historical evidence must not be
-- silently rewritten or deleted. V91 and its Flyway checksum stay unchanged.
ALTER TABLE public.organization_model_policies
    ADD CONSTRAINT chk_model_policy_usd_finite_v92 CHECK (
        (max_request_cost_usd IS NULL OR max_request_cost_usd <> 'NaN'::numeric)
        AND (monthly_budget_usd IS NULL OR monthly_budget_usd <> 'NaN'::numeric)
    ),
    ADD CONSTRAINT chk_model_policy_rub_dates_finite_v92 CHECK (
        rub_per_usd IS NULL
        OR (
            isfinite(rub_quote_date)
            AND isfinite(rub_effective_date)
            AND isfinite(rub_fetched_at)
            AND isfinite(created_at)
        )
    );

-- Constrained numeric(p,s) already rejects positive/negative infinity.
-- V91 still enforces completeness, time consistency and exact conversion.
COMMENT ON CONSTRAINT chk_model_policy_usd_finite_v92 ON public.organization_model_policies IS
    'Reject PostgreSQL numeric NaN for all USD ceilings, including USD-only policies';
COMMENT ON CONSTRAINT chk_model_policy_rub_dates_finite_v92 ON public.organization_model_policies IS
    'RUB conversion evidence must contain finite dates and timestamps';
