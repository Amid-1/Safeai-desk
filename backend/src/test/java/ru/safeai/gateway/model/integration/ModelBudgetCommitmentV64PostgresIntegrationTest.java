package ru.safeai.gateway.model.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.integration.AbstractChatPostgresIntegrationTest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration coverage for immutable V64 route-time budget evidence.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.usage.rollup.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelBudgetCommitmentV64PostgresIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    @Test
    void knownFreeRoutePersistsSeparatedKnownBudgetEvidenceAndItIsImmutable() {
        Instant now =
                ModelTestFixtures.NOW;

        String modelKey =
                "openai:gpt-test:budget-v64:" + UUID.randomUUID();

        ModelCatalogEntry template =
                ModelTestFixtures.freeEntry();

        ModelCatalogEntry entry =
                new ModelCatalogEntry(
                        UUID.randomUUID(),
                        modelKey,
                        1,
                        "openai",
                        "gpt-test",
                        "GPT Test",
                        template.lifecycle(),
                        template.maxInputTokens(),
                        template.maxOutputTokens(),
                        template.capabilities(),
                        template.inputModalities(),
                        template.outputModalities(),
                        template.retentionStatus(),
                        template.retentionDays(),
                        template.trainingUseStatus(),
                        template.pricingStatus(),
                        template.pricingComplete(),
                        template.inputUsdPer1mTokens(),
                        template.cachedInputUsdPer1mTokens(),
                        template.cacheWriteInputUsdPer1mTokens(),
                        template.outputUsdPer1mTokens(),
                        template.extraPricingJson(),
                        template.pricingVersion(),
                        now.minusSeconds(120),
                        template.source(),
                        USER_ID,
                        now.minusSeconds(180)
                );

        ModelCatalogRepository catalogRepository =
                new ModelCatalogRepository(jdbcTemplate);
        OrganizationModelPolicyRepository policyRepository =
                new OrganizationModelPolicyRepository(jdbcTemplate);
        ModelRouteDecisionRepository decisionRepository =
                new ModelRouteDecisionRepository(jdbcTemplate);

        catalogRepository.insert(entry);

        ModelIntegrationTestSupport.insertAllowAllPolicy(
                jdbcTemplate,
                ORGANIZATION_ID,
                USER_ID,
                1,
                null,
                new BigDecimal("10.000000000000"),
                now.minusSeconds(90)
        );

        RuntimeModelStatusService runtime =
                mock(RuntimeModelStatusService.class);
        RuntimeModelStatusResponse status =
                ModelTestFixtures.freeRuntime();
        when(runtime.current()).thenReturn(status);

        ModelRoutingService routing =
                ModelIntegrationTestSupport.routingService(
                        jdbcTemplate,
                        catalogRepository,
                        policyRepository,
                        decisionRepository,
                        runtime,
                        mock(AuditEventService.class),
                        ModelTestFixtures.CLOCK
                );

        UUID turnId =
                UUID.randomUUID();
        UUID clientRequestId =
                UUID.randomUUID();
        UUID operationId =
                UUID.randomUUID();

        UUID messageId =
                insertUserMessage(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        clientRequestId,
                        "hello",
                        now.minusSeconds(20)
                );

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_USER")
                        )
                );

        UUID decisionId =
                new TransactionTemplate(transactionManager)
                        .execute(statusTx -> {
                            var result =
                                    routing.decide(
                                            new ModelRouteRequest(
                                                    ORGANIZATION_ID,
                                                    USER_ID,
                                                    CHAT_ID,
                                                    turnId,
                                                    clientRequestId,
                                                    ModelTestFixtures.REQUEST_HASH,
                                                    modelKey,
                                                    "hello",
                                                    List.of(),
                                                    Set.of(),
                                                    0L
                                            ),
                                            principal
                                    );

                            jdbcTemplate.update(
                                    """
                                    insert into public.chat_turns (
                                        id, session_id, organization_id, user_id,
                                        client_request_id, request_content_hash,
                                        provider_operation_id, user_message_id,
                                        state, processing_token, lease_until,
                                        provider, requested_model,
                                        knowledge_mode, knowledge_base_id,
                                        outcome_ambiguous,
                                        model_route_decision_id,
                                        created_at, updated_at, version
                                    ) values (
                                        ?, ?, ?, ?, ?, ?, ?, ?,
                                        'PROCESSING', ?, ?,
                                        'openai', 'gpt-test',
                                        'GENERAL', null,
                                        false, ?, ?, ?, 0
                                    )
                                    """,
                                    turnId,
                                    CHAT_ID,
                                    ORGANIZATION_ID,
                                    USER_ID,
                                    clientRequestId,
                                    ModelTestFixtures.REQUEST_HASH,
                                    operationId,
                                    messageId,
                                    UUID.randomUUID(),
                                    timestamp(now.plusSeconds(180)),
                                    result.decisionId(),
                                    timestamp(now),
                                    timestamp(now)
                            );

                            return result.decisionId();
                        });

        assertThat(decisionId)
                .isNotNull();

        BudgetRow row =
                jdbcTemplate.queryForObject(
                        """
                        select
                            actual_cost_usd,
                            reserved_cost_usd,
                            unknown_cost_count,
                            cost_state
                        from public.model_route_budget_commitments_v64
                        where model_route_decision_id = ?
                        """,
                        (rs, rowNum) ->
                                new BudgetRow(
                                        rs.getBigDecimal("actual_cost_usd"),
                                        rs.getBigDecimal("reserved_cost_usd"),
                                        rs.getLong("unknown_cost_count"),
                                        rs.getString("cost_state")
                                ),
                        decisionId
                );

        assertThat(row)
                .isNotNull();
        assertThat(row.actualCostUsd())
                .isEqualByComparingTo("0");
        assertThat(row.reservedCostUsd())
                .isEqualByComparingTo("0");
        assertThat(row.unknownCostCount())
                .isZero();
        assertThat(row.costState())
                .isEqualTo("KNOWN_WITHIN");

        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        """
                        update public.model_route_budget_commitments_v64
                        set unknown_cost_count = 1
                        where model_route_decision_id = ?
                        """,
                        decisionId
                )
        )
                .isInstanceOf(DataAccessException.class)
                .satisfies(error -> {
                    SQLException sql =
                            ModelIntegrationTestSupport.rootSqlException(error);

                    assertThat(sql.getSQLState())
                            .isEqualTo("23514");
                });
    }

    private record BudgetRow(
            BigDecimal actualCostUsd,
            BigDecimal reservedCostUsd,
            long unknownCostCount,
            String costState
    ) {
    }
}
