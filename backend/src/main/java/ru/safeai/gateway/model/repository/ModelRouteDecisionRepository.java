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
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
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

    public ModelRouteDecisionRepository(
            JdbcTemplate jdbc
    ) {
        this.jdbc =
                Objects.requireNonNull(
                        jdbc,
                        "jdbc не должен быть null"
                );
    }

    public void lockOrganizationBudget(
            UUID organizationId
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        jdbc.execute(
                (ConnectionCallback<Void>) connection -> {
                    try (
                            PreparedStatement statement =
                                    connection.prepareStatement(
                                            "select "
                                                    + "pg_advisory_xact_lock("
                                                    + "hashtextextended(?, 0))"
                                    )
                    ) {
                        statement.setString(
                                1,
                                "safeai:model-budget:"
                                        + organizationId
                        );

                        statement.execute();
                    }

                    return null;
                }
        );
    }

    /**
     * Forces the V45 deferred ALLOWED-decision -> exact ChatTurn constraint
     * while reservation is still DB-only.
     */
    public void validateAllowedTurnLinkBeforeExternalSideEffects() {
        jdbc.execute(
                "set constraints "
                        + "ctrg_model_route_decision_turn_v45 "
                        + "immediate"
        );
    }

    public Optional<ModelRouteDecision> findByRequest(
            UUID chatId,
            UUID clientRequestId
    ) {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );

        List<ModelRouteDecision> rows =
                jdbc.query(
                        "select "
                                + SELECT_COLUMNS
                                + " from model_route_decisions "
                                + "where chat_id = ? "
                                + "and client_request_id = ?",
                        this::map,
                        chatId,
                        clientRequestId
                );

        return rows.stream()
                .findFirst();
    }

    public Optional<ModelRouteDecision> findById(
            UUID id
    ) {
        Objects.requireNonNull(
                id,
                "id не должен быть null"
        );

        List<ModelRouteDecision> rows =
                jdbc.query(
                        "select "
                                + SELECT_COLUMNS
                                + " from model_route_decisions "
                                + "where id = ?",
                        this::map,
                        id
                );

        return rows.stream()
                .findFirst();
    }

    /**
     * Conservative committed monthly cost.
     *
     * <p>SUCCEEDED uses exact assistant cost only when
     * {@code usage_status=AVAILABLE} and pricing is PRICED/FREE.
     * PROCESSING/AMBIGUOUS and incomplete successful rows retain the immutable
     * route estimate.</p>
     */
    public MonthlyCostSnapshot loadCommittedMonthlyCostSnapshot(
            UUID organizationId,
            Instant periodStart,
            Instant periodEnd
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                periodStart,
                "periodStart не должен быть null"
        );

        Objects.requireNonNull(
                periodEnd,
                "periodEnd не должен быть null"
        );

        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException(
                    "periodEnd должен быть позже periodStart"
            );
        }

        MonthlyCostSnapshot snapshot =
                jdbc.queryForObject(
                        """
                        with committed as (
                            select
                                decision.estimated_max_cost_usd,
                                turn.state,
                                assistant.cost_usd
                                    as assistant_cost_usd,
                                (
                                    turn.state = 'SUCCEEDED'
                                    and assistant.usage_status = 'AVAILABLE'
                                    and assistant.pricing_status
                                        in ('PRICED', 'FREE')
                                    and assistant.cost_usd is not null
                                ) as exact_success_cost_known,
                                (
                                    decision.pricing_complete = true
                                    and decision.estimated_max_cost_usd
                                        is not null
                                ) as route_estimate_known
                            from model_route_decisions decision
                            join chat_turns turn
                              on turn.id = decision.chat_turn_id
                             and turn.model_route_decision_id =
                                 decision.id
                             and turn.organization_id =
                                 decision.organization_id
                             and turn.user_id =
                                 decision.user_id
                             and turn.session_id =
                                 decision.chat_id
                            left join chat_messages assistant
                              on assistant.id =
                                 turn.assistant_message_id
                             and assistant.organization_id =
                                 turn.organization_id
                            where decision.organization_id = ?
                              and decision.outcome = 'ALLOWED'
                              and decision.created_at >= ?
                              and decision.created_at < ?
                              and turn.state in (
                                  'PROCESSING',
                                  'AMBIGUOUS',
                                  'SUCCEEDED'
                              )
                        )
                        select
                            coalesce(
                                sum(
                                    case
                                        when state = 'SUCCEEDED'
                                             and exact_success_cost_known
                                            then assistant_cost_usd
                                        when estimated_max_cost_usd
                                             is not null
                                            then estimated_max_cost_usd
                                        else 0
                                    end
                                ),
                                0
                            ) as committed_cost_usd,
                            count(*) filter (
                                where not (
                                    (
                                        state = 'SUCCEEDED'
                                        and exact_success_cost_known
                                    )
                                    or route_estimate_known
                                )
                            )::bigint
                                as unknown_committed_cost_count
                        from committed
                        """,
                        (rs, ignored) ->
                                new MonthlyCostSnapshot(
                                        rs.getBigDecimal(
                                                "committed_cost_usd"
                                        ),
                                        rs.getLong(
                                                "unknown_committed_cost_count"
                                        )
                                ),
                        organizationId,
                        Timestamp.from(
                                periodStart
                        ),
                        Timestamp.from(
                                periodEnd
                        )
                );

        return Objects.requireNonNull(
                snapshot,
                "Monthly model cost aggregate "
                        + "must return exactly one row"
        );
    }

    public ModelRouteDecision insert(
            ModelRouteDecision decision
    ) {
        Objects.requireNonNull(
                decision,
                "decision не должен быть null"
        );

        int updated =
                jdbc.update(
                        connection ->
                                prepareInsert(
                                        connection,
                                        decision
                                )
                );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Model route decision insert affected "
                            + updated
                            + " rows"
            );
        }

        return decision;
    }

    private PreparedStatement prepareInsert(
            Connection connection,
            ModelRouteDecision decision
    ) throws SQLException {
        PreparedStatement statement =
                connection.prepareStatement(
                        """
                        insert into model_route_decisions (
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
                        ) values (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                        )
                        """
                );

        int index = 1;

        statement.setObject(
                index++,
                decision.id()
        );

        statement.setObject(
                index++,
                decision.organizationId()
        );

        statement.setObject(
                index++,
                decision.userId()
        );

        statement.setObject(
                index++,
                decision.chatId()
        );

        setNullableUuid(
                statement,
                index++,
                decision.chatTurnId()
        );

        statement.setObject(
                index++,
                decision.clientRequestId()
        );

        statement.setString(
                index++,
                decision.requestContentHash()
        );

        statement.setString(
                index++,
                decision.requestedModelKey()
        );

        setNullableUuid(
                statement,
                index++,
                decision.selectedCatalogEntryId()
        );

        setNullableInteger(
                statement,
                index++,
                decision.selectedCatalogVersion()
        );

        statement.setString(
                index++,
                decision.selectedModelKey()
        );

        statement.setString(
                index++,
                decision.selectedProvider()
        );

        statement.setString(
                index++,
                decision.selectedProviderModelId()
        );

        setNullableUuid(
                statement,
                index++,
                decision.policyId()
        );

        setNullableInteger(
                statement,
                index++,
                decision.policyVersion()
        );

        statement.setArray(
                index++,
                capabilityArray(
                        connection,
                        decision.requiredCapabilities()
                )
        );

        statement.setString(
                index++,
                decision.inputAccountingVersion()
        );

        setNullableLong(
                statement,
                index++,
                decision.additionalInputUnitUpperBound()
        );

        setNullableLong(
                statement,
                index++,
                decision.estimatedInputTokens()
        );

        setNullableLong(
                statement,
                index++,
                decision.estimatedOutputTokens()
        );

        statement.setBigDecimal(
                index++,
                decision.estimatedMaxCostUsd()
        );

        statement.setBigDecimal(
                index++,
                decision.monthlyBudgetUsd()
        );

        statement.setBigDecimal(
                index++,
                decision.monthlySpentUsd()
        );

        statement.setBigDecimal(
                index++,
                decision.monthlyProjectedUsd()
        );

        statement.setBoolean(
                index++,
                decision.monthlyCostKnown()
        );

        statement.setString(
                index++,
                decision.budgetEnforcement() == null
                        ? null
                        : decision.budgetEnforcement()
                                .name()
        );

        statement.setBoolean(
                index++,
                decision.budgetExceeded()
        );

        statement.setBoolean(
                index++,
                decision.pricingComplete()
        );

        statement.setString(
                index++,
                decision.outcome()
                        .name()
        );

        statement.setString(
                index++,
                decision.reason()
                        .name()
        );

        statement.setShort(
                index++,
                decision.decisionIntegrityVersion()
        );

        statement.setString(
                index++,
                decision.decisionSha256()
        );

        statement.setTimestamp(
                index,
                Timestamp.from(
                        decision.createdAt()
                )
        );

        return statement;
    }

    private ModelRouteDecision map(
            ResultSet rs,
            int ignoredRowNumber
    ) throws SQLException {
        return new ModelRouteDecision(
                rs.getObject(
                        "id",
                        UUID.class
                ),
                rs.getObject(
                        "organization_id",
                        UUID.class
                ),
                rs.getObject(
                        "user_id",
                        UUID.class
                ),
                rs.getObject(
                        "chat_id",
                        UUID.class
                ),
                rs.getObject(
                        "chat_turn_id",
                        UUID.class
                ),
                rs.getObject(
                        "client_request_id",
                        UUID.class
                ),
                rs.getString(
                        "request_content_hash"
                ),
                rs.getString(
                        "requested_model_key"
                ),
                rs.getObject(
                        "selected_catalog_entry_id",
                        UUID.class
                ),
                rs.getObject(
                        "selected_catalog_version",
                        Integer.class
                ),
                rs.getString(
                        "selected_model_key"
                ),
                rs.getString(
                        "selected_provider"
                ),
                rs.getString(
                        "selected_provider_model_id"
                ),
                rs.getObject(
                        "policy_id",
                        UUID.class
                ),
                rs.getObject(
                        "policy_version",
                        Integer.class
                ),
                readCapabilities(
                        rs.getArray(
                                "required_capabilities"
                        )
                ),
                rs.getString(
                        "input_accounting_version"
                ),
                rs.getObject(
                        "additional_input_unit_upper_bound",
                        Long.class
                ),
                rs.getObject(
                        "estimated_input_tokens",
                        Long.class
                ),
                rs.getObject(
                        "estimated_output_tokens",
                        Long.class
                ),
                rs.getBigDecimal(
                        "estimated_max_cost_usd"
                ),
                rs.getBigDecimal(
                        "monthly_budget_usd"
                ),
                rs.getBigDecimal(
                        "monthly_spent_usd"
                ),
                rs.getBigDecimal(
                        "monthly_projected_usd"
                ),
                rs.getBoolean(
                        "monthly_cost_known"
                ),
                nullableBudgetEnforcement(
                        rs.getString(
                                "budget_enforcement"
                        )
                ),
                rs.getBoolean(
                        "budget_exceeded"
                ),
                rs.getBoolean(
                        "pricing_complete"
                ),
                ModelRouteOutcome.valueOf(
                        rs.getString(
                                "outcome"
                        )
                ),
                ModelRouteReason.valueOf(
                        rs.getString(
                                "reason"
                        )
                ),
                rs.getShort(
                        "decision_integrity_version"
                ),
                rs.getString(
                        "decision_sha256"
                ),
                rs.getTimestamp(
                        "created_at"
                ).toInstant()
        );
    }

    private static Array capabilityArray(
            Connection connection,
            Set<ModelCapability> capabilities
    ) throws SQLException {
        Objects.requireNonNull(
                connection,
                "connection не должен быть null"
        );

        Objects.requireNonNull(
                capabilities,
                "capabilities не должен быть null"
        );

        String[] values =
                capabilities.stream()
                        .map(Enum::name)
                        .sorted()
                        .toArray(
                                String[]::new
                        );

        return connection.createArrayOf(
                "text",
                values
        );
    }

    private static Set<ModelCapability> readCapabilities(
            Array array
    ) throws SQLException {
        if (array == null) {
            return Set.of();
        }

        Object raw =
                array.getArray();

        String[] values;

        if (raw instanceof String[] strings) {
            values =
                    strings;
        } else if (raw instanceof Object[] objects) {
            values =
                    Arrays.stream(
                                    objects
                            )
                            .map(
                                    String::valueOf
                            )
                            .toArray(
                                    String[]::new
                            );
        } else {
            throw new SQLException(
                    "Unsupported required_capabilities "
                            + "JDBC array type"
            );
        }

        EnumSet<ModelCapability> result =
                EnumSet.noneOf(
                        ModelCapability.class
                );

        for (String value : values) {
            result.add(
                    ModelCapability.valueOf(
                            value
                    )
            );
        }

        return Set.copyOf(
                result
        );
    }

    private static BudgetEnforcement nullableBudgetEnforcement(
            String value
    ) {
        return value == null
                ? null
                : BudgetEnforcement.valueOf(
                        value
                );
    }

    private static void setNullableUuid(
            PreparedStatement statement,
            int index,
            UUID value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(
                    index,
                    Types.OTHER
            );
        } else {
            statement.setObject(
                    index,
                    value
            );
        }
    }

    private static void setNullableInteger(
            PreparedStatement statement,
            int index,
            Integer value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(
                    index,
                    Types.INTEGER
            );
        } else {
            statement.setInt(
                    index,
                    value
            );
        }
    }

    private static void setNullableLong(
            PreparedStatement statement,
            int index,
            Long value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(
                    index,
                    Types.BIGINT
            );
        } else {
            statement.setLong(
                    index,
                    value
            );
        }
    }

    public record MonthlyCostSnapshot(
            BigDecimal committedCostUsd,
            long unknownCommittedCostCount
    ) {

        public MonthlyCostSnapshot {
            committedCostUsd =
                    committedCostUsd == null
                            ? BigDecimal.ZERO
                            : committedCostUsd;
        }
    }
}