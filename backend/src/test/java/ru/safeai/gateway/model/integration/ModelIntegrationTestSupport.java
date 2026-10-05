package ru.safeai.gateway.model.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.ModelRouteRequestMutexRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared fixtures for current Model Control Plane PostgreSQL integration tests.
 *
 * <p>This helper intentionally uses the same durable boundaries as production:
 * a real request-mutex repository, real JDBC control-plane repositories and
 * V64 binding-version=2 prepared-request receipts. It does not mock away DB
 * serialization or execution binding.</p>
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
final class ModelIntegrationTestSupport {

    static final String BASE_REQUEST_SHA256 = "a".repeat(64);
    static final String PREPARED_REQUEST_SHA256 = "b".repeat(64);

    private ModelIntegrationTestSupport() {
    }

    static ModelRoutingService routingService(
            JdbcTemplate jdbc,
            ModelCatalogRepository catalogRepository,
            OrganizationModelPolicyRepository policyRepository,
            ModelRouteDecisionRepository decisionRepository,
            RuntimeModelStatusService runtimeStatusService,
            AuditEventService audit,
            Clock clock
    ) {
        Objects.requireNonNull(jdbc, "jdbc");
        return new ModelRoutingService(
                Objects.requireNonNull(catalogRepository, "catalogRepository"),
                Objects.requireNonNull(policyRepository, "policyRepository"),
                Objects.requireNonNull(decisionRepository, "decisionRepository"),
                new ModelRouteRequestMutexRepository(jdbc),
                Objects.requireNonNull(runtimeStatusService, "runtimeStatusService"),
                Objects.requireNonNull(audit, "audit"),
                Objects.requireNonNull(clock, "clock")
        );
    }

    /**
     * Inserts one explicit configured policy. Empty allow/deny arrays mean that
     * the policy is configured and does not restrict model keys.
     */
    static void insertAllowAllPolicy(
            JdbcTemplate jdbc,
            UUID organizationId,
            UUID createdByUserId,
            int version,
            BigDecimal maxRequestCostUsd,
            BigDecimal monthlyBudgetUsd,
            Instant createdAt
    ) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(createdByUserId, "createdByUserId");
        Objects.requireNonNull(createdAt, "createdAt");

        int affected = jdbc.update(
                """
                insert into public.organization_model_policies (
                    id, organization_id, version, enabled,
                    allow_model_keys, deny_model_keys, default_model_key,
                    max_input_tokens, max_output_tokens,
                    max_request_cost_usd, monthly_budget_usd,
                    budget_enforcement, require_complete_pricing,
                    require_no_training, require_zero_data_retention,
                    created_by_user_id, created_at
                ) values (
                    ?, ?, ?, true,
                    '{}'::text[], '{}'::text[], null,
                    null, null, ?, ?,
                    'SOFT', false, false, false,
                    ?, ?
                )
                """,
                statement -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, organizationId);
                    statement.setInt(3, version);

                    if (maxRequestCostUsd == null) {
                        statement.setNull(4, Types.NUMERIC);
                    } else {
                        statement.setBigDecimal(4, maxRequestCostUsd);
                    }

                    if (monthlyBudgetUsd == null) {
                        statement.setNull(5, Types.NUMERIC);
                    } else {
                        statement.setBigDecimal(5, monthlyBudgetUsd);
                    }

                    statement.setObject(6, createdByUserId);
                    statement.setTimestamp(7, Timestamp.from(createdAt));
                }
        );

        if (affected != 1) {
            throw new IllegalStateException(
                    "Exactly one organization model policy must be inserted"
            );
        }
    }

    /**
     * Synthetic GENERAL-mode DB fixture representing a fully prepared request.
     *
     * <p>The fixture deliberately uses the governed decision's full reserved
     * input envelope as prepared_input_units. That keeps the row valid without
     * inventing a tokenizer-specific value and exercises the upper boundary of
     * the V64 check. No RAG provenance is present because the ChatTurn is
     * GENERAL.</p>
     */
    static void insertGeneralPreparedBindingV64(
            JdbcTemplate jdbc,
            UUID decisionId,
            UUID chatTurnId,
            UUID providerOperationId,
            String baseRequestSha256,
            String preparedRequestSha256,
            Instant createdAt
    ) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(providerOperationId, "providerOperationId");
        requireSha256(baseRequestSha256, "baseRequestSha256");
        requireSha256(preparedRequestSha256, "preparedRequestSha256");
        Objects.requireNonNull(createdAt, "createdAt");

        int affected = jdbc.update(
                """
                insert into public.model_route_prepared_requests_v62 (
                    model_route_decision_id,
                    chat_turn_id,
                    provider_operation_id,
                    base_request_sha256,
                    prepared_request_sha256,
                    input_accounting_version,
                    prepared_input_units,
                    created_at,
                    knowledge_base_id,
                    retrieval_run_id,
                    rag_context_sha256,
                    binding_version
                )
                select
                    d.id,
                    ?,
                    ?,
                    ?,
                    ?,
                    d.input_accounting_version,
                    d.estimated_input_tokens,
                    ?,
                    null,
                    null,
                    null,
                    2
                from public.model_route_decisions d
                where d.id = ?
                """,
                statement -> {
                    statement.setObject(1, chatTurnId);
                    statement.setObject(2, providerOperationId);
                    statement.setString(3, baseRequestSha256);
                    statement.setString(4, preparedRequestSha256);
                    statement.setTimestamp(5, Timestamp.from(createdAt));
                    statement.setObject(6, decisionId);
                }
        );

        if (affected != 1) {
            throw new IllegalStateException(
                    "Exactly one V64 GENERAL prepared binding must be inserted"
            );
        }
    }

    static SQLException rootSqlException(Throwable error) {
        Throwable root = Objects.requireNonNull(error, "error");
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (!(root instanceof SQLException sql)) {
            throw new AssertionError(
                    "Expected SQLException root cause, got " + root.getClass().getName(),
                    error
            );
        }
        return sql;
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be lowercase SHA-256"
            );
        }
    }
}
