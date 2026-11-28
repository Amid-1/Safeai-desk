package ru.safeai.gateway.common.budget;

/** Shared PostgreSQL classification for platform counters and organization drill-down. */
public final class PlatformBudgetClassification {
    private PlatformBudgetClassification() {}

    public enum Status { WITHIN, NEAR, EXCEEDED, NO_LIMIT, UNKNOWN, DISABLED, UNCONFIGURED }

    public static final String CTE = """
            with ledger as (
                select organization_id,
                       coalesce(sum(actual_cost_usd) filter (where not carry_forward), 0)
                         + coalesce(sum(reserved_cost_usd), 0) as committed,
                       coalesce(sum(unknown_cost_count), 0) as unknown_count
                from public.model_cost_commitment_ledger_v62
                where carry_forward or (ledger_at >= :budgetStart and ledger_at < :budgetEnd)
                group by organization_id
            ), classified as (
                select o.id as organization_id, o.enabled as organization_enabled,
                       case
                         when p.id is null then 'UNCONFIGURED'
                         when not p.enabled then 'DISABLED'
                         when p.monthly_budget_usd is null then 'NO_LIMIT'
                         when coalesce(l.committed, 0) > p.monthly_budget_usd then 'EXCEEDED'
                         when coalesce(l.unknown_count, 0) > 0 then 'UNKNOWN'
                         when coalesce(l.committed, 0) >= p.monthly_budget_usd * 0.8 then 'NEAR'
                         else 'WITHIN'
                       end as state
                from public.organizations o
                left join lateral (
                    select id, enabled, monthly_budget_usd
                    from public.organization_model_policies
                    where organization_id = o.id order by version desc limit 1
                ) p on true
                left join ledger l on l.organization_id = o.id
            )
            """;
}
