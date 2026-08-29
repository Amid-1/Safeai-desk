package ru.safeai.gateway.model.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class OrganizationModelPolicyRepository {

    private final JdbcTemplate jdbc;

    public OrganizationModelPolicyRepository(
            JdbcTemplate jdbc
    ) {
        this.jdbc = Objects.requireNonNull(
                jdbc,
                "jdbc не должен быть null"
        );
    }

    /**
     * Cheap existence check for read paths. Mutation paths must use
     * {@link #lockOrganization(UUID)} so version allocation remains serialized.
     */
    public boolean organizationExists(
            UUID organizationId
    ) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from organizations where id = ?)",
                Boolean.class,
                organizationId
        );
        return Boolean.TRUE.equals(exists);
    }

    /** Row lock gives deterministic version allocation and policy updates. */
    public boolean lockOrganization(
            UUID organizationId
    ) {
        List<UUID> rows = jdbc.query(
                "select id from organizations where id = ? for update",
                (resultSet, ignoredRowNumber) ->
                        resultSet.getObject("id", UUID.class),
                organizationId
        );
        return !rows.isEmpty();
    }

    public Optional<OrganizationModelPolicy> findLatest(
            UUID organizationId
    ) {
        List<OrganizationModelPolicy> rows = jdbc.query(
                """
                select
                    id, organization_id, version, enabled,
                    allow_model_keys, deny_model_keys, default_model_key,
                    max_input_tokens, max_output_tokens,
                    max_request_cost_usd, monthly_budget_usd,
                    budget_enforcement, require_complete_pricing,
                    require_no_training, require_zero_data_retention,
                    created_by_user_id, created_at
                from organization_model_policies
                where organization_id = ?
                order by version desc
                limit 1
                """,
                this::map,
                organizationId
        );
        return rows.stream().findFirst();
    }

    public OrganizationModelPolicy insert(
            OrganizationModelPolicy policy
    ) {
        int updated = jdbc.update(
                connection -> prepareInsert(
                        connection,
                        policy
                )
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Model policy version insert affected "
                            + updated
                            + " rows"
            );
        }
        return policy;
    }

    private PreparedStatement prepareInsert(
            Connection connection,
            OrganizationModelPolicy policy
    ) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                """
                insert into organization_model_policies (
                    id, organization_id, version, enabled,
                    allow_model_keys, deny_model_keys, default_model_key,
                    max_input_tokens, max_output_tokens,
                    max_request_cost_usd, monthly_budget_usd,
                    budget_enforcement, require_complete_pricing,
                    require_no_training, require_zero_data_retention,
                    created_by_user_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        );

        statement.setObject(1, policy.id());
        statement.setObject(2, policy.organizationId());
        statement.setInt(3, policy.version());
        statement.setBoolean(4, policy.enabled());
        statement.setArray(
                5,
                textArray(
                        connection,
                        policy.allowModelKeys()
                )
        );
        statement.setArray(
                6,
                textArray(
                        connection,
                        policy.denyModelKeys()
                )
        );
        statement.setString(7, policy.defaultModelKey());
        setNullableInteger(statement, 8, policy.maxInputTokens());
        setNullableInteger(statement, 9, policy.maxOutputTokens());
        statement.setBigDecimal(10, policy.maxRequestCostUsd());
        statement.setBigDecimal(11, policy.monthlyBudgetUsd());
        statement.setString(12, policy.budgetEnforcement().name());
        statement.setBoolean(13, policy.requireCompletePricing());
        statement.setBoolean(14, policy.requireNoTraining());
        statement.setBoolean(15, policy.requireZeroDataRetention());
        statement.setObject(16, policy.createdByUserId());
        statement.setTimestamp(17, Timestamp.from(policy.createdAt()));
        return statement;
    }

    private OrganizationModelPolicy map(
            ResultSet resultSet,
            int ignoredRowNumber
    ) throws SQLException {
        return new OrganizationModelPolicy(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getInt("version"),
                resultSet.getBoolean("enabled"),
                stringSet(resultSet.getArray("allow_model_keys")),
                stringSet(resultSet.getArray("deny_model_keys")),
                resultSet.getString("default_model_key"),
                resultSet.getObject("max_input_tokens", Integer.class),
                resultSet.getObject("max_output_tokens", Integer.class),
                resultSet.getBigDecimal("max_request_cost_usd"),
                resultSet.getBigDecimal("monthly_budget_usd"),
                BudgetEnforcement.valueOf(
                        resultSet.getString("budget_enforcement")
                ),
                resultSet.getBoolean("require_complete_pricing"),
                resultSet.getBoolean("require_no_training"),
                resultSet.getBoolean("require_zero_data_retention"),
                resultSet.getObject("created_by_user_id", UUID.class),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static Array textArray(
            Connection connection,
            Set<String> values
    ) throws SQLException {
        String[] sorted = values.stream()
                .sorted()
                .toArray(String[]::new);
        return connection.createArrayOf(
                "text",
                sorted
        );
    }

    private static Set<String> stringSet(
            Array array
    ) throws SQLException {
        if (array == null) {
            return Set.of();
        }

        try {
            Object raw = array.getArray();
            if (!(raw instanceof String[] strings)) {
                throw new SQLException(
                        "Expected PostgreSQL text[]"
                );
            }

            LinkedHashSet<String> values =
                    new LinkedHashSet<>(
                            Arrays.asList(strings)
                    );

            return Collections.unmodifiableSet(values);
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
}
