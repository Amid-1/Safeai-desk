package ru.safeai.gateway.model.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class ModelRouteDecisionRepository {

    private static final String SELECT_COLUMNS = """
            id,
            organization_id,
            user_id,
            chat_id,
            chat_turn_id,
            client_request_id,
            request_content_hash,
            requested_model_key,
            selected_catalog_entry_id,
            selected_catalog_version,
            selected_model_key,
            selected_provider,
            selected_provider_model_id,
            policy_id,
            policy_version,
            required_capabilities,
            input_accounting_version,
            additional_input_unit_upper_bound,
            estimated_input_tokens,
            estimated_output_tokens,
            estimated_max_cost_usd,
            monthly_budget_usd,
            monthly_spent_usd,
            monthly_projected_usd,
            monthly_cost_known,
            budget_enforcement,
            budget_exceeded,
            pricing_complete,
            outcome,
            reason,
            decision_integrity_version,
            decision_sha256,
            created_at
            """;

    private final JdbcTemplate jdbc;

    public ModelRouteDecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc не должен быть null");
    }

    public void lockOrganizationBudget(UUID organizationId) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))"
            )) {
                statement.setString(1, "safeai:model-budget:" + organizationId);
                statement.execute();
            }
            return null;
        });
    }

    /**
     * Forces the V45 deferred ALLOWED-decision -> exact ChatTurn constraint
     * trigger to run while reservation is still DB-only. This must be called
     * after the ChatTurn row is flushed and before any Redis/provider side
     * effect.
     */
    public void validateAllowedTurnLinkBeforeExternalSideEffects() {
        jdbc.execute(
                "set constraints ctrg_model_route_decision_turn_v45 immediate"
        );
    }

    public Optional<ModelRouteDecision> findByRequest(UUID chatId, UUID clientRequestId) {
        List<ModelRouteDecision> rows = jdbc.query(
                "select " + SELECT_COLUMNS + " from model_route_decisions "
                        + "where chat_id = ? and client_request_id = ?",
                this::map,
                chatId,
                clientRequestId
        );
        return rows.stream().findFirst();
    }

    public Optional<ModelRouteDecision> findById(UUID id) {
        List<ModelRouteDecision> rows = jdbc.query(
                "select " + SELECT_COLUMNS + " from model_route_decisions where id = ?",
                this::map,
                id
        );
        return rows.stream().findFirst();
    }

    /**
     * V50/V51 physical-attempt ledger + still-in-flight request reservations.
     * <p>
     * A plan with attempts is NEVER charged through assistant.cost_usd. Pre-V50
     * turns without an execution plan retain an explicit legacy fallback.
     * <p>
     * Known physical costs are the sum of billable attempt evidence, including
     * attempts belonging to a FAILED logical turn. STARTED/AMBIGUOUS or
     * unpriced successful attempts mark the monthly cost unverifiable. HARD
     * enforcement then rejects instead of treating an estimate as exact.
     * <p>
     * A PROCESSING turn reserves one future/current physical attempt until a
     * successful physical attempt completes, even between failed retries.
     * Reservations are deliberately conservative, not actual provider spend.
     * Accounting month follows the existing route-decision created_at contract.
     */
    public MonthlyCostSnapshot loadCommittedMonthlyCostSnapshot(
            UUID organizationId,
            Instant periodStart,
            Instant periodEnd
    ) {
        MonthlyCostSnapshot snapshot = jdbc.queryForObject(
                """
                with selected_turns as (
                    select
                        d.id as decision_id,
                        d.estimated_max_cost_usd,
                        d.pricing_complete,
                        t.state,
                        plan.id as plan_id,
                        assistant.cost_usd as legacy_assistant_cost,
                        assistant.usage_status as legacy_usage_status,
                        assistant.pricing_status as legacy_pricing_status
                    from model_route_decisions d
                    join chat_turns t on t.model_route_decision_id = d.id
                    left join model_execution_plans plan
                           on plan.chat_turn_id = t.id
                          and plan.model_route_decision_id = d.id
                    left join chat_messages assistant
                           on assistant.id = t.assistant_message_id
                          and plan.id is null
                    where d.organization_id = ?
                      and d.outcome = 'ALLOWED'
                      and d.created_at >= ?
                      and d.created_at < ?
                      and t.state in ('PROCESSING', 'AMBIGUOUS', 'SUCCEEDED', 'FAILED')
                ),
                attempt_totals as (
                    select
                        s.decision_id,
                        count(a.id)::bigint as attempt_count,
                        count(a.id) filter (
                            where a.outcome = 'STARTED'
                        )::bigint as started_count,
                        count(a.id) filter (
                            where a.outcome = 'SUCCEEDED'
                        )::bigint as succeeded_count,
                        coalesce(sum(
                            case
                                when a.outcome <> 'STARTED'
                                 and a.pricing_status in ('PRICED', 'FREE')
                                 and a.cost_usd is not null
                                    then a.cost_usd
                                else 0
                            end
                        ), 0) as evidenced_cost_usd,
                        count(a.id) filter (
                            where a.outcome in ('STARTED', 'AMBIGUOUS')
                               or (a.outcome = 'SUCCEEDED'
                                   and coalesce((
                                       a.usage_status = 'AVAILABLE'
                                       and a.specialized_dimensions_valid = true
                                       and a.pricing_status in ('PRICED', 'FREE')
                                       and a.cost_usd is not null
                                   ), false) = false)
                               or (a.outcome = 'FAILED'
                                   and a.outcome_certainty <> 'KNOWN_NOT_EXECUTED'
                                   and coalesce((
                                       a.usage_status = 'AVAILABLE'
                                       and a.specialized_dimensions_valid = true
                                       and a.pricing_status in ('PRICED', 'FREE')
                                       and a.cost_usd is not null
                                   ), false) = false)
                        )::bigint as uncertain_count
                    from selected_turns s
                    join model_execution_attempts a
                      on a.execution_plan_id = s.plan_id
                    group by s.decision_id
                ),
                ledger as (
                    select
                        s.*,
                        coalesce(a.attempt_count, 0) as attempt_count,
                        coalesce(a.started_count, 0) as started_count,
                        coalesce(a.succeeded_count, 0) as succeeded_count,
                        coalesce(a.evidenced_cost_usd, 0) as evidenced_cost_usd,
                        coalesce(a.uncertain_count, 0) as uncertain_count,
                        (s.state = 'SUCCEEDED'
                            and s.legacy_usage_status = 'AVAILABLE'
                            and s.legacy_pricing_status in ('PRICED', 'FREE')
                            and s.legacy_assistant_cost is not null
                        ) as legacy_exact
                    from selected_turns s
                    left join attempt_totals a on a.decision_id = s.decision_id
                )
                select
                    coalesce(sum(
                        case
                            when plan_id is null and legacy_exact
                                then legacy_assistant_cost
                            when plan_id is null
                                then coalesce(estimated_max_cost_usd, 0)
                            else evidenced_cost_usd
                                 + case
                                     when state = 'PROCESSING'
                                          and (started_count > 0
                                               or succeeded_count = 0)
                                         then coalesce(estimated_max_cost_usd, 0)
                                     else 0
                                   end
                        end
                    ), 0) as committed_cost_usd,
                    coalesce(sum(
                        case
                            when plan_id is null
                                then case
                                    when legacy_exact then 0
                                    when state = 'FAILED'
                                         or state = 'AMBIGUOUS'
                                         or pricing_complete = false
                                         or estimated_max_cost_usd is null
                                        then 1
                                    else 0
                                end
                            else uncertain_count
                                 + case
                                     when state in ('SUCCEEDED', 'AMBIGUOUS')
                                          and attempt_count = 0 then 1
                                     when state = 'SUCCEEDED'
                                          and succeeded_count = 0 then 1
                                     when state = 'PROCESSING'
                                          and (started_count > 0
                                               or succeeded_count = 0)
                                          and (pricing_complete = false
                                               or estimated_max_cost_usd is null)
                                         then 1
                                     else 0
                                   end
                        end
                    ), 0)::bigint as unknown_committed_cost_count
                from ledger
                """,
                (rs, ignoredRowNumber) -> new MonthlyCostSnapshot(
                        rs.getBigDecimal("committed_cost_usd"),
                        rs.getLong("unknown_committed_cost_count")
                ),
                organizationId,
                Timestamp.from(periodStart),
                Timestamp.from(periodEnd)
        );

        return Objects.requireNonNull(
                snapshot,
                "Monthly model cost aggregate must return exactly one row"
        );
    }

    public ModelRouteDecision insert(ModelRouteDecision decision) {
        int updated = jdbc.update(connection -> prepareInsert(connection, decision));
        if (updated != 1) {
            throw new IllegalStateException(
                    "Model route decision insert affected " + updated + " rows"
            );
        }
        return decision;
    }

    private PreparedStatement prepareInsert(
            Connection connection,
            ModelRouteDecision decision
    ) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                """
                insert into model_route_decisions (
                    id, organization_id, user_id, chat_id, chat_turn_id,
                    client_request_id, request_content_hash, requested_model_key,
                    selected_catalog_entry_id, selected_catalog_version,
                    selected_model_key, selected_provider, selected_provider_model_id,
                    policy_id, policy_version, required_capabilities,
                    input_accounting_version, additional_input_unit_upper_bound,
                    estimated_input_tokens, estimated_output_tokens,
                    estimated_max_cost_usd, monthly_budget_usd,
                    monthly_spent_usd, monthly_projected_usd,
                    monthly_cost_known, budget_enforcement, budget_exceeded,
                    pricing_complete, outcome, reason,
                    decision_integrity_version, decision_sha256, created_at
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """
        );

        int index = 1;
        statement.setObject(index++, decision.id());
        statement.setObject(index++, decision.organizationId());
        statement.setObject(index++, decision.userId());
        statement.setObject(index++, decision.chatId());
        statement.setObject(index++, decision.chatTurnId());
        statement.setObject(index++, decision.clientRequestId());
        statement.setString(index++, decision.requestContentHash());
        statement.setString(index++, decision.requestedModelKey());
        statement.setObject(index++, decision.selectedCatalogEntryId());
        setNullableInteger(statement, index++, decision.selectedCatalogVersion());
        statement.setString(index++, decision.selectedModelKey());
        statement.setString(index++, decision.selectedProvider());
        statement.setString(index++, decision.selectedProviderModelId());
        statement.setObject(index++, decision.policyId());
        setNullableInteger(statement, index++, decision.policyVersion());
        statement.setArray(index++, capabilityArray(connection, decision.requiredCapabilities()));
        statement.setString(index++, decision.inputAccountingVersion());
        setNullableLong(statement, index++, decision.additionalInputUnitUpperBound());
        setNullableLong(statement, index++, decision.estimatedInputTokens());
        setNullableLong(statement, index++, decision.estimatedOutputTokens());
        statement.setBigDecimal(index++, decision.estimatedMaxCostUsd());
        statement.setBigDecimal(index++, decision.monthlyBudgetUsd());
        statement.setBigDecimal(index++, decision.monthlySpentUsd());
        statement.setBigDecimal(index++, decision.monthlyProjectedUsd());
        statement.setBoolean(index++, decision.monthlyCostKnown());
        statement.setString(
                index++,
                decision.budgetEnforcement() == null
                        ? null
                        : decision.budgetEnforcement().name()
        );
        statement.setBoolean(index++, decision.budgetExceeded());
        statement.setBoolean(index++, decision.pricingComplete());
        statement.setString(index++, decision.outcome().name());
        statement.setString(index++, decision.reason().name());
        statement.setShort(index++, decision.decisionIntegrityVersion());
        statement.setString(index++, decision.decisionSha256());
        statement.setTimestamp(index, Timestamp.from(decision.createdAt()));
        return statement;
    }

    private ModelRouteDecision map(ResultSet rs, int ignoredRowNumber) throws SQLException {
        return new ModelRouteDecision(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("chat_id", UUID.class),
                rs.getObject("chat_turn_id", UUID.class),
                rs.getObject("client_request_id", UUID.class),
                rs.getString("request_content_hash"),
                rs.getString("requested_model_key"),
                rs.getObject("selected_catalog_entry_id", UUID.class),
                rs.getObject("selected_catalog_version", Integer.class),
                rs.getString("selected_model_key"),
                rs.getString("selected_provider"),
                rs.getString("selected_provider_model_id"),
                rs.getObject("policy_id", UUID.class),
                rs.getObject("policy_version", Integer.class),
                capabilities(rs.getArray("required_capabilities")),
                rs.getString("input_accounting_version"),
                rs.getObject("additional_input_unit_upper_bound", Long.class),
                rs.getObject("estimated_input_tokens", Long.class),
                rs.getObject("estimated_output_tokens", Long.class),
                rs.getBigDecimal("estimated_max_cost_usd"),
                rs.getBigDecimal("monthly_budget_usd"),
                rs.getBigDecimal("monthly_spent_usd"),
                rs.getBigDecimal("monthly_projected_usd"),
                rs.getBoolean("monthly_cost_known"),
                readBudgetEnforcement(rs),
                rs.getBoolean("budget_exceeded"),
                rs.getBoolean("pricing_complete"),
                ModelRouteOutcome.valueOf(rs.getString("outcome")),
                ModelRouteReason.valueOf(rs.getString("reason")),
                rs.getShort("decision_integrity_version"),
                rs.getString("decision_sha256"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static BudgetEnforcement readBudgetEnforcement(ResultSet rs) throws SQLException {
        String value = rs.getString("budget_enforcement");
        return value == null ? null : BudgetEnforcement.valueOf(value);
    }

    private static Array capabilityArray(
            Connection connection,
            Set<ModelCapability> capabilities
    ) throws SQLException {
        String[] values = capabilities.stream()
                .map(Enum::name)
                .sorted()
                .toArray(String[]::new);
        return connection.createArrayOf("text", values);
    }

    private static Set<ModelCapability> capabilities(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        try {
            Object raw = array.getArray();
            if (!(raw instanceof String[] strings)) {
                throw new SQLException("Expected PostgreSQL text[] for required_capabilities");
            }
            EnumSet<ModelCapability> result = EnumSet.noneOf(ModelCapability.class);
            Arrays.stream(strings).map(ModelCapability::valueOf).forEach(result::add);
            return Set.copyOf(result);
        } finally {
            array.free();
        }
    }

    private static void setNullableInteger(
            PreparedStatement statement,
            int index,
            Integer value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableLong(
            PreparedStatement statement,
            int index,
            Long value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    public record MonthlyCostSnapshot(
            BigDecimal committedCostUsd,
            long unknownCommittedCostCount
    ) {
        public MonthlyCostSnapshot {
            committedCostUsd = committedCostUsd == null
                    ? BigDecimal.ZERO
                    : committedCostUsd;
            if (unknownCommittedCostCount < 0L) {
                throw new IllegalArgumentException(
                        "unknownCommittedCostCount не может быть отрицательным"
                );
            }
        }
    }
}
