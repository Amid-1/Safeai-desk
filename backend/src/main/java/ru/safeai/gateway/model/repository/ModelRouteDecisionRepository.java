package ru.safeai.gateway.model.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelBudgetCommitmentSnapshot;
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOrganizationBudget(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
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
    @Transactional(propagation = Propagation.MANDATORY)
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
     * V62 hardens the V60 commitment model so a pending reservation exists only
     * before attempt #1 or after an explicitly retry-safe failed attempt.
     * Provider-evidenced costs, active upper-bound reservations and unknown
     * liability remain separate. Physical month uses attempt started_at (UTC);
     * active reservations carry across months. Pre-ledger history keeps its
     * explicitly labelled legacy route timestamp. The existing decision
     * monthlySpentUsd field remains total commitment for hash compatibility;
     * actual spend and reservations are available separately on this snapshot.
     */
    public MonthlyCostSnapshot loadCommittedMonthlyCostSnapshot(
            UUID organizationId,
            Instant periodStart,
            Instant periodEnd
    ) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (!periodStart.isBefore(periodEnd)) {
            throw new IllegalArgumentException("Monthly cost period must have a positive duration");
        }
        MonthlyCostSnapshot snapshot = jdbc.queryForObject(
                """
                with scoped as (
                    select * from public.model_cost_commitment_ledger_v62
                    where organization_id = ?
                      and (carry_forward or (ledger_at >= ? and ledger_at < ?))
                )
                select
                    coalesce(sum(actual_cost_usd) filter (where not carry_forward), 0) as actual_cost_usd,
                    coalesce(sum(reserved_cost_usd), 0) as reserved_cost_usd,
                    coalesce(sum(unknown_cost_count), 0)::bigint as unknown_committed_cost_count
                from scoped
                """,
                (rs, ignoredRowNumber) -> new MonthlyCostSnapshot(
                        rs.getBigDecimal("actual_cost_usd").add(rs.getBigDecimal("reserved_cost_usd")),
                        rs.getLong("unknown_committed_cost_count"),
                        rs.getBigDecimal("actual_cost_usd"),
                        rs.getBigDecimal("reserved_cost_usd")
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

    /** Uses the immutable policy version referenced by the sealed decision, never the latest policy. */
    public boolean requiresSinglePhysicalAttempt(UUID decisionId) {
        Boolean single = jdbc.queryForObject("""
                select (d.budget_enforcement = 'HARD' and d.monthly_budget_usd is not null)
                    or coalesce((p.enabled = true and p.max_request_cost_usd is not null), false)
                from model_route_decisions d
                left join organization_model_policies p
                  on p.id = d.policy_id and p.version = d.policy_version
                 and p.organization_id = d.organization_id
                where d.id = ? and d.outcome = 'ALLOWED'
                """, Boolean.class, decisionId);
        return Boolean.TRUE.equals(single);
    }

    public void insertBudgetCommitmentSnapshot(
            UUID decisionId,
            ModelBudgetCommitmentSnapshot snapshot,
            Instant createdAt
    ) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(createdAt, "createdAt");
        int updated = jdbc.update(
                """
                insert into public.model_route_budget_commitments_v64 (
                    model_route_decision_id,
                    actual_cost_usd,
                    reserved_cost_usd,
                    unknown_cost_count,
                    cost_state,
                    created_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
                decisionId,
                snapshot.actualCostUsd(),
                snapshot.reservedCostUsd(),
                snapshot.unknownCostCount(),
                snapshot.commitmentState().name(),
                Timestamp.from(createdAt)
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Model route budget commitment insert affected " + updated + " rows"
            );
        }
    }

    public Optional<ModelBudgetCommitmentSnapshot> findBudgetCommitmentSnapshot(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        List<ModelBudgetCommitmentSnapshot> rows = jdbc.query(
                """
                select actual_cost_usd, reserved_cost_usd, unknown_cost_count, cost_state
                from public.model_route_budget_commitments_v64
                where model_route_decision_id = ?
                """,
                (rs, ignored) -> new ModelBudgetCommitmentSnapshot(
                        rs.getBigDecimal("actual_cost_usd"),
                        rs.getBigDecimal("reserved_cost_usd"),
                        rs.getLong("unknown_cost_count"),
                        ru.safeai.gateway.model.domain.MonthlyCommitmentState.valueOf(
                                rs.getString("cost_state")
                        )
                ),
                decisionId
        );
        return rows.stream().findFirst();
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
        EnumSet<ModelCapability> result = EnumSet.noneOf(ModelCapability.class);
        for (String value : JdbcTextArrayReader.read(array, "required_capabilities")) {
            try {
                result.add(ModelCapability.valueOf(value));
            } catch (IllegalArgumentException exception) {
                throw new SQLException("Invalid model capability in required_capabilities: " + value, exception);
            }
        }
        return Set.copyOf(result);
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
            long unknownCommittedCostCount,
            BigDecimal actualCostUsd,
            BigDecimal reservedCostUsd
    ) {
        public MonthlyCostSnapshot(BigDecimal committedCostUsd, long unknownCommittedCostCount) {
            this(committedCostUsd, unknownCommittedCostCount, committedCostUsd, BigDecimal.ZERO);
        }

        public MonthlyCostSnapshot {
            committedCostUsd = committedCostUsd == null
                    ? BigDecimal.ZERO
                    : committedCostUsd;
            actualCostUsd = actualCostUsd == null ? BigDecimal.ZERO : actualCostUsd;
            reservedCostUsd = reservedCostUsd == null ? BigDecimal.ZERO : reservedCostUsd;
            if (actualCostUsd.signum() < 0 || reservedCostUsd.signum() < 0
                    || committedCostUsd.compareTo(actualCostUsd.add(reservedCostUsd)) != 0) {
                throw new IllegalArgumentException("Invalid cost commitment components");
            }
            if (unknownCommittedCostCount < 0L) {
                throw new IllegalArgumentException(
                        "unknownCommittedCostCount не может быть отрицательным"
                );
            }
        }
    }
}
