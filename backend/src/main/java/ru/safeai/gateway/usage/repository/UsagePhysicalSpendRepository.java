package ru.safeai.gateway.usage.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.dto.UsagePhysicalSpendResponse;
import ru.safeai.gateway.usage.dto.UsagePhysicalReconciliationResponse;
import ru.safeai.gateway.usage.dto.UsageProviderRow;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Evidence-backed rollup of all physical attempts; no join to chat_messages. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class UsagePhysicalSpendRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UsagePhysicalSpendRepository(UsageJdbcClients clients) {
        this.jdbc = Objects.requireNonNull(clients, "clients").query();
    }

    public UsagePhysicalSpendResponse findCoverage(
            Instant from, Instant to, UUID organizationId
    ) {
        PhysicalQueryScope scope = scope(from, to, organizationId);
        MapSqlParameterSource params = scope.parameters();

        // Sum each a.id exactly once; retries are distinct physical provider calls.
        // KNOWN_NOT_EXECUTED is the only certainty that provably incurs no cost.
        String sql = scope.sql("""
                select
                  count(*)::bigint as attempt_count,
                  count(*) filter (where a.outcome = 'STARTED')::bigint as started_count,
                  count(*) filter (where a.outcome = 'AMBIGUOUS')::bigint as ambiguous_count,
                  count(*) filter (where a.outcome_certainty = 'KNOWN_EXECUTED')::bigint
                    as known_executed_count,
                  count(*) filter (where a.outcome_certainty = 'KNOWN_NOT_EXECUTED')::bigint
                    as known_not_executed_count,
                  count(*) filter (where a.outcome_certainty = 'KNOWN_REJECTED')::bigint
                    as known_rejected_count,
                  count(*) filter (
                    where a.outcome_certainty <> 'KNOWN_NOT_EXECUTED'
                      and (a.pricing_status is distinct from 'PRICED')
                      and (a.pricing_status is distinct from 'FREE')
                  )::bigint as cost_unknown_count,
                  count(*) filter (
                    where a.outcome_certainty <> 'KNOWN_NOT_EXECUTED'
                      and a.usage_status is distinct from 'AVAILABLE'
                  )::bigint as usage_unknown_count,
                  coalesce(sum(a.input_tokens) filter (
                    where a.usage_status = 'AVAILABLE'), 0)::bigint as input_tokens,
                  coalesce(sum(a.output_tokens) filter (
                    where a.usage_status = 'AVAILABLE'), 0)::bigint as output_tokens,
                  coalesce(sum(a.cost_usd) filter (
                    where a.pricing_status in ('PRICED', 'FREE')), 0)::numeric
                    as known_cost_usd
                from public.model_execution_attempts a
                join public.model_execution_plans p on p.id = a.execution_plan_id
                where a.started_at >= :from
                  and a.started_at < :to
                """, "");

        return Objects.requireNonNull(jdbc.queryForObject(sql, params, (rs, row) ->
                new UsagePhysicalSpendResponse(
                        from, to, organizationId, "PHYSICAL_ATTEMPT_STARTED_AT", "USD",
                        rs.getLong("attempt_count"),
                        rs.getLong("started_count"),
                        rs.getLong("ambiguous_count"),
                        rs.getLong("known_executed_count"),
                        rs.getLong("known_not_executed_count"),
                        rs.getLong("known_rejected_count"),
                        rs.getLong("cost_unknown_count"),
                        rs.getLong("usage_unknown_count"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        Objects.requireNonNullElse(rs.getBigDecimal("known_cost_usd"),
                                BigDecimal.ZERO)
                )), "Physical attempt aggregate returned null");
    }

    /** Исторический провайдер каждой физической попытки, включая повторы. */
    public List<UsageProviderRow> findProviders(
            Instant from, Instant to, UUID organizationId, String model, UUID userId
    ) {
        PhysicalQueryScope scope = scope(from, to, organizationId);
        var parameters = scope.parameters().addValue("model", model, java.sql.Types.VARCHAR)
                .addValue("userId", userId, java.sql.Types.OTHER);
        String sql = scope.sql("""
                select a.provider_type, count(*) as attempts,
                  count(*) filter (where a.outcome in ('STARTED', 'AMBIGUOUS')) as unresolved,
                  count(*) filter (where a.outcome_certainty <> 'KNOWN_NOT_EXECUTED'
                    and a.pricing_status is distinct from 'PRICED'
                    and a.pricing_status is distinct from 'FREE') as unknown_cost,
                  count(*) filter (where a.outcome_certainty <> 'KNOWN_NOT_EXECUTED'
                    and a.usage_status is distinct from 'AVAILABLE') as unknown_usage,
                  coalesce(sum(a.input_tokens) filter (where a.usage_status = 'AVAILABLE'), 0) as input,
                  coalesce(sum(a.output_tokens) filter (where a.usage_status = 'AVAILABLE'), 0) as output,
                  coalesce(sum(a.cost_usd) filter (where a.pricing_status in ('PRICED', 'FREE')), 0) as cost
                from public.model_execution_attempts a
                join public.model_execution_plans p on p.id = a.execution_plan_id
                join public.chat_turns t on t.id = p.chat_turn_id
                where a.started_at >= :from and a.started_at < :to
                  and (:model is null or p.requested_model = :model)
                  and (cast(:userId as uuid) is null or t.user_id = :userId)
                """, """
                group by a.provider_type order by a.provider_type limit 1001
                """);
        var rows = jdbc.query(sql, parameters, (rs, row) -> new UsageProviderRow(
                rs.getString("provider_type"), Long.toString(rs.getLong("attempts")),
                Long.toString(rs.getLong("unresolved")), Long.toString(rs.getLong("unknown_cost")),
                Long.toString(rs.getLong("unknown_usage")), Long.toString(rs.getLong("input")),
                Long.toString(rs.getLong("output")), rs.getBigDecimal("cost").toPlainString()));
        if (rows.size() > 1000) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "Слишком много провайдеров");
        }
        return rows;
    }

    /**
     * A plan is selected by any attempt started in the requested interval,
     * then ALL its attempts are priced, including those across the time edge.
     * This avoids presenting truncated multi-attempt plans as matching.
     */
    public UsagePhysicalReconciliationResponse reconcile(
            Instant from, Instant to, UUID organizationId
    ) {
        PhysicalQueryScope scope = scope(from, to, organizationId);
        MapSqlParameterSource params = scope.parameters();

        String sql = scope.sql("""
                with selected_plans as (
                    select distinct a.execution_plan_id
                    from public.model_execution_attempts a
                    join public.model_execution_plans p
                      on p.id = a.execution_plan_id
                    where a.started_at >= :from
                      and a.started_at < :to
                """, """
                ), per_plan as (
                    select
                        p.id,
                        count(*) filter (
                            where a.outcome_certainty <> 'KNOWN_NOT_EXECUTED'
                              and a.pricing_status is distinct from 'PRICED'
                              and a.pricing_status is distinct from 'FREE'
                        ) as unknown_count,
                        coalesce(sum(a.cost_usd) filter (
                            where a.pricing_status in ('PRICED', 'FREE')
                        ), 0) as physical_known_cost,
                        m.cost_usd as message_known_cost,
                        (m.status = 'COMPLETED'
                            and m.pricing_status in ('PRICED', 'FREE')
                            and m.cost_usd is not null) as message_priced
                    from selected_plans selected
                    join public.model_execution_plans p on p.id = selected.execution_plan_id
                    join public.model_execution_attempts a on a.execution_plan_id = p.id
                    join public.chat_turns t on t.id = p.chat_turn_id
                    left join public.chat_messages m
                        on m.id = t.assistant_message_id and m.role = 'ASSISTANT'
                    group by p.id, m.status, m.cost_usd, m.pricing_status
                )
                select
                  count(*)::bigint as plans,
                  count(*) filter (
                      where unknown_count = 0 and message_priced is true
                        and physical_known_cost = message_known_cost
                  )::bigint as matched,
                  count(*) filter (
                      where unknown_count = 0 and message_priced is true
                        and physical_known_cost <> message_known_cost
                  )::bigint as mismatched,
                  count(*) filter (
                      where unknown_count <> 0 or message_priced is not true
                  )::bigint as unverifiable
                from per_plan
                """);

        return Objects.requireNonNull(jdbc.queryForObject(sql, params, (rs, row) ->
                new UsagePhysicalReconciliationResponse(
                        from, to, organizationId, "PLANS_WITH_PHYSICAL_START_IN_RANGE",
                        rs.getLong("plans"), rs.getLong("matched"),
                        rs.getLong("mismatched"), rs.getLong("unverifiable"))),
                "Physical reconciliation aggregate returned null");
    }

    /**
     * Single validation and bind-parameter boundary for all physical reports.
     * SQL structure stays fixed; only the optional tenant predicate is selected.
     */
    private static PhysicalQueryScope scope(
            Instant from, Instant to, UUID organizationId
    ) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from должен быть раньше to");
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
        if (organizationId == null) {
            return new PhysicalQueryScope(params, "");
        }
        params.addValue("organizationId", organizationId);
        return new PhysicalQueryScope(
                params, "and p.organization_id = :organizationId");
    }

    private record PhysicalQueryScope(
            MapSqlParameterSource parameters, String tenantPredicate
    ) {
        /** Never concatenate a named parameter directly with the next SQL token. */
        private String sql(String prefix, String suffix) {
            return String.join("\n", prefix, tenantPredicate, suffix);
        }
    }
}
