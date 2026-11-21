-- SafeAI-Desk/backend/src/main/resources/db/migration/V91__organization_model_policy_rub_budget.sql
-- Existing USD-only policies remain byte-for-byte unchanged. Applied quotes
-- live in the already immutable versioned policy, not in mutable preferences.
ALTER TABLE public.organization_model_policies
    ADD COLUMN max_request_cost_rub numeric(30,12),
    ADD COLUMN monthly_budget_rub numeric(30,12),
    ADD COLUMN rub_per_usd numeric(18,8),
    ADD COLUMN rub_quote_date date,
    ADD COLUMN rub_effective_date date,
    ADD COLUMN rub_fetched_at timestamptz;

ALTER TABLE public.organization_model_policies
    ADD CONSTRAINT chk_model_policy_rub_budget_v91 CHECK (
        (max_request_cost_rub IS NULL AND monthly_budget_rub IS NULL
            AND rub_per_usd IS NULL AND rub_quote_date IS NULL
            AND rub_effective_date IS NULL AND rub_fetched_at IS NULL)
        OR
        (rub_per_usd IS NOT NULL AND rub_per_usd > 0 AND rub_per_usd <> 'NaN'::numeric
            AND rub_quote_date IS NOT NULL AND rub_effective_date IS NOT NULL
            AND rub_fetched_at IS NOT NULL
            AND rub_effective_date BETWEEN rub_quote_date - 14 AND rub_quote_date
            AND (rub_fetched_at AT TIME ZONE 'Europe/Moscow')::date = rub_quote_date
            AND rub_fetched_at <= created_at
            AND (max_request_cost_rub IS NOT NULL OR monthly_budget_rub IS NOT NULL)
            AND (max_request_cost_rub IS NULL OR (max_request_cost_rub >= 0 AND max_request_cost_rub <> 'NaN'::numeric))
            AND (monthly_budget_rub IS NULL OR (monthly_budget_rub >= 0 AND monthly_budget_rub <> 'NaN'::numeric))
            -- Integer quotient gives exact floor to 12 places without an
            -- intermediate PostgreSQL division rounding the result upwards.
            AND max_request_cost_usd IS NOT DISTINCT FROM
                (div(max_request_cost_rub * 1000000000000, nullif(rub_per_usd, 0)) * 0.000000000001)
            AND monthly_budget_usd IS NOT DISTINCT FROM
                (div(monthly_budget_rub * 1000000000000, nullif(rub_per_usd, 0)) * 0.000000000001))
    );

COMMENT ON COLUMN public.organization_model_policies.rub_per_usd IS
    'Bank of Russia official RUB per 1 USD applied at policy creation; immutable historical evidence';
