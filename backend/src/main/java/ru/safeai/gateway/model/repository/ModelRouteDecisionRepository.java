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
     * Conservative committed-cost snapshot.
     *
     * <p>For SUCCEEDED turns an assistant cost is exact only when V17 pricing
     * metadata proves PRICED/FREE with AVAILABLE usage. Otherwise we may use
     * the route estimate. Incomplete estimates are retained as a lower-bound
     * estimate but still increment unknownCommittedCostCount; HARD enforcement
     * therefore fails closed. For PROCESSING/AMBIGUOUS, only a complete route
     * estimate makes the cost fully knowable.</p>
     */
    public MonthlyCostSnapshot loadCommittedMonthlyCostSnapshot(
            UUID organizationId,
            Instant periodStart,
            Instant periodEnd
    ) {
        MonthlyCostSnapshot snapshot = jdbc.queryForObject(
                """
                with committed as (
                    select
                        turn_row.state,
                        decision.pricing_complete as decision_pricing_complete,
                        decision.estimated_max_cost_usd,
                        assistant.cost_usd as assistant_cost_usd,
                        assistant.usage_status as assistant_usage_status,
                        assistant.pricing_status as assistant_pricing_status,
                        case
                            when turn_row.state = 'SUCCEEDED'
                                 and assistant.usage_status = 'AVAILABLE'
                                 and assistant.pricing_status in ('PRICED', 'FREE')
                                 and assistant.cost_usd is not null
                                then true
                            else false
                        end as exact_success_cost_known,
                        case
                            when decision.pricing_complete = true
                                 and decision.estimated_max_cost_usd is not null
                                then true
                            else false
                        end as route_estimate_known
                    from model_route_decisions decision
                    join chat_turns turn_row
                      on turn_row.model_route_decision_id = decision.id
                    left join chat_messages assistant
                      on assistant.id = turn_row.assistant_message_id
                    where decision.organization_id = ?
                      and decision.outcome = 'ALLOWED'
                      and decision.created_at >= ?
                      and decision.created_at < ?
                      and turn_row.state in ('PROCESSING', 'AMBIGUOUS', 'SUCCEEDED')
                )
                select
                    coalesce(sum(
                        case
                            when state = 'SUCCEEDED' and exact_success_cost_known
                                then assistant_cost_usd
                            when estimated_max_cost_usd is not null
                                then estimated_max_cost_usd
                            else 0
                        end
                    ), 0) as committed_cost_usd,
                    count(*) filter (
                        where not (
                            (state = 'SUCCEEDED' and exact_success_cost_known)
                            or route_estimate_known
                        )
                    )::bigint as unknown_committed_cost_count
                from committed
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
                    estimated_input_tokens, estimated_output_tokens,
                    estimated_max_cost_usd, monthly_budget_usd,
                    monthly_spent_usd, monthly_projected_usd,
                    monthly_cost_known, budget_enforcement, budget_exceeded,
                    pricing_complete, outcome, reason,
                    decision_integrity_version, decision_sha256, created_at
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
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
