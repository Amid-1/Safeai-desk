package ru.safeai.gateway.model.integration;

import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
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
 * Focused V62/V64/V65 regression suite for the durable final-request binding.
 *
 * <p>Older V50-V54 attempt sequencing tests should not be weakened to make V65
 * pass. They should seed one valid prepared binding in their happy-path setup,
 * while this class proves that V65 rejects attempts when the binding is absent.</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.usage.rollup.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelExecutionPreparedBindingV65PostgresIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    private static final Instant START = ModelTestFixtures.NOW;
    private static final String MODEL = "gpt-test";
    private static final String CHECK_VIOLATION_SQLSTATE = "23514";

    private UUID decisionId;
    private UUID turnId;
    private UUID operationId;
    private UUID planId;
    private Instant physicalAttemptAt;

    @BeforeEach
    void arrangeValidGeneralExecutionWithoutPreparedBinding() {
        String modelKey =
                "openai:" + MODEL + ":v65:" + UUID.randomUUID();

        ModelCatalogEntry template =
                ModelTestFixtures.freeEntry();

        ModelCatalogEntry entry =
                new ModelCatalogEntry(
                        UUID.randomUUID(),
                        modelKey,
                        1,
                        "openai",
                        MODEL,
                        MODEL,
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
                        START.minusSeconds(120),
                        template.source(),
                        USER_ID,
                        START.minusSeconds(180)
                );

        ModelCatalogRepository catalogRepository =
                new ModelCatalogRepository(jdbcTemplate);
        OrganizationModelPolicyRepository policyRepository =
                new OrganizationModelPolicyRepository(jdbcTemplate);
        ModelRouteDecisionRepository decisionRepository =
                new ModelRouteDecisionRepository(jdbcTemplate);

        catalogRepository.insert(entry, "FLAT_TOKEN_V1");

        ModelIntegrationTestSupport.insertAllowAllPolicy(
                jdbcTemplate,
                ORGANIZATION_ID,
                USER_ID,
                1,
                null,
                null,
                START.minusSeconds(90)
        );

        RuntimeModelStatusService runtime =
                mock(RuntimeModelStatusService.class);
        RuntimeModelStatusResponse runtimeStatus =
                ModelTestFixtures.freeRuntime();
        when(runtime.current()).thenReturn(runtimeStatus);

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

        turnId = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();
        operationId = UUID.randomUUID();
        planId = UUID.randomUUID();

        UUID messageId =
                insertUserMessage(
                        CHAT_ID,
                        ORGANIZATION_ID,
                        clientRequestId,
                        "hello",
                        START.minusSeconds(20)
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

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    decisionId =
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
                            ).decisionId();

                    // V67 checks the PROCESSING lease against the actual
                    // PostgreSQL clock, not the fixed model-routing clock.
                    // Preserve historical routing provenance while building
                    // a genuinely live, fenced execution fixture.
                    java.sql.Timestamp databaseNow = java.util.Objects.requireNonNull(
                            jdbcTemplate.queryForObject(
                                    "select clock_timestamp()",
                                    java.sql.Timestamp.class
                            ),
                            "PostgreSQL clock_timestamp() must not be null"
                    );
                    Instant liveNow = databaseNow.toInstant();
                    physicalAttemptAt = liveNow.minusSeconds(2);
                    Instant turnCreatedAt = physicalAttemptAt.minusSeconds(5);
                    Instant leaseUntil = liveNow.plusSeconds(180);

                    jdbcTemplate.update(
                            """
                            insert into public.chat_turns (
                                id, session_id, organization_id, user_id,
                                client_request_id, request_content_hash,
                                provider_operation_id, user_message_id,
                                state, processing_token, lease_until,
                                provider_call_started_at,
                                provider, requested_model,
                                knowledge_mode, knowledge_base_id,
                                outcome_ambiguous,
                                model_route_decision_id,
                                created_at, updated_at, version
                            ) values (
                                ?, ?, ?, ?, ?, ?, ?, ?,
                                'PROCESSING', ?, ?, ?,
                                'openai', ?, 'GENERAL', null,
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
                            timestamp(leaseUntil),
                            timestamp(physicalAttemptAt),
                            MODEL,
                            decisionId,
                            timestamp(turnCreatedAt),
                            timestamp(physicalAttemptAt)
                    );

                    jdbcTemplate.update(
                            """
                            insert into public.model_route_reserved_requests (
                                model_route_decision_id,
                                chat_turn_id,
                                provider_operation_id,
                                base_request_sha256,
                                created_at
                            ) values (?, ?, ?, ?, ?)
                            """,
                            decisionId,
                            turnId,
                            operationId,
                            ModelIntegrationTestSupport.BASE_REQUEST_SHA256,
                            timestamp(START)
                    );

                    jdbcTemplate.update(
                            """
                            insert into public.model_execution_plans (
                                id,
                                provider_operation_id,
                                chat_turn_id,
                                organization_id,
                                model_route_decision_id,
                                requested_model,
                                created_at
                            ) values (?, ?, ?, ?, ?, ?, ?)
                            """,
                            planId,
                            operationId,
                            turnId,
                            ORGANIZATION_ID,
                            decisionId,
                            MODEL,
                            timestamp(START)
                    );
                });

        assertThat(decisionId).isNotNull();
        assertThat(physicalAttemptAt).isNotNull();
        assertThat(physicalAttemptAt).isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    void physicalAttemptWithoutPreparedBindingIsRejectedByV65() {
        assertCheckRejection(
                this::insertFirstAttempt,
                "V65 physical attempt has no current binding_version=2 durable final-request binding"
        );

        assertThat(countAttempts())
                .isZero();
    }

    @Test
    void physicalAttemptWithV64GeneralBindingIsAllowed() {
        insertValidGeneralPreparedBinding();

        insertFirstAttempt();

        assertThat(countAttempts())
                .isEqualTo(1);
    }

    @Test
    void preparedBindingMustMatchTheExactSealedBaseRequest() {
        assertCheckRejection(
                () -> ModelIntegrationTestSupport.insertGeneralPreparedBindingV64(
                        jdbcTemplate,
                        decisionId,
                        turnId,
                        operationId,
                        "c".repeat(64),
                        ModelIntegrationTestSupport.PREPARED_REQUEST_SHA256,
                        START
                ),
                "V64 prepared request has no exact sealed pre-RAG reservation"
        );

        assertThat(countPreparedBindings())
                .isZero();
    }

    @Test
    void v64GeneralBindingVersionTwoRejectsRagProvenance() {
        assertCheckRejection(
                () -> jdbcTemplate.update(
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
                            ?,
                            ?,
                            ?,
                            2
                        from public.model_route_decisions d
                        where d.id = ?
                        """,
                        turnId,
                        operationId,
                        ModelIntegrationTestSupport.BASE_REQUEST_SHA256,
                        ModelIntegrationTestSupport.PREPARED_REQUEST_SHA256,
                        timestamp(START),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "d".repeat(64),
                        decisionId
                ),
                "V64 GENERAL prepared request cannot carry RAG provenance"
        );

        assertThat(countPreparedBindings())
                .isZero();
    }

    @Test
    void generalPreparedBindingPersistsTheExactPreparedRequestHash() {
        String preparedRequestSha256 = "f".repeat(64);

        ModelIntegrationTestSupport.insertGeneralPreparedBindingV64(
                jdbcTemplate,
                decisionId,
                turnId,
                operationId,
                ModelIntegrationTestSupport.BASE_REQUEST_SHA256,
                preparedRequestSha256,
                START
        );

        String persisted = jdbcTemplate.queryForObject(
                """
                select prepared_request_sha256
                from public.model_route_prepared_requests_v62
                where model_route_decision_id = ?
                """,
                String.class,
                decisionId
        );

        assertThat(persisted)
                .isEqualTo(preparedRequestSha256);
    }

    @Test
    void preparedBindingIsAppendOnly() {
        insertValidGeneralPreparedBinding();

        assertCheckViolation(
                () -> jdbcTemplate.update(
                        """
                        update public.model_route_prepared_requests_v62
                        set prepared_request_sha256 = ?
                        where model_route_decision_id = ?
                        """,
                        "e".repeat(64),
                        decisionId
                )
        );

        assertCheckViolation(
                () -> jdbcTemplate.update(
                        """
                        delete from public.model_route_prepared_requests_v62
                        where model_route_decision_id = ?
                        """,
                        decisionId
                )
        );

        assertThat(countPreparedBindings())
                .isEqualTo(1);
    }

    @Test
    void v64AndV65BindingTriggersAreInstalled() {
        Integer preparedGuard =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from pg_trigger
                        where tgname = 'trg_model_route_prepared_requests_guard_v64'
                          and not tgisinternal
                        """,
                        Integer.class
                );

        Integer attemptGuard =
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from pg_trigger
                        where tgname = 'trg_execution_attempt_prepared_binding_v65'
                          and not tgisinternal
                        """,
                        Integer.class
                );

        assertThat(preparedGuard)
                .isEqualTo(1);
        assertThat(attemptGuard)
                .isEqualTo(1);
    }

    private void insertValidGeneralPreparedBinding() {
        ModelIntegrationTestSupport.insertGeneralPreparedBindingV64(
                jdbcTemplate,
                decisionId,
                turnId,
                operationId,
                ModelIntegrationTestSupport.BASE_REQUEST_SHA256,
                ModelIntegrationTestSupport.PREPARED_REQUEST_SHA256,
                START
        );
    }

    private void insertFirstAttempt() {
        jdbcTemplate.update(
                """
                insert into public.model_execution_attempts (
                    id,
                    execution_plan_id,
                    attempt_number,
                    provider_attempt_id,
                    provider_type,
                    provider_configuration_ref,
                    provider_configuration_version,
                    deployment_ref,
                    deployment_version,
                    requested_physical_model,
                    started_at,
                    outcome,
                    outcome_certainty,
                    retry_safety,
                    fallback_safety,
                    created_at
                ) values (
                    ?, ?, ?, ?,
                    'openai',
                    'static:openai',
                    'static-v1',
                    'static:openai:gpt-test',
                    'static-v1',
                    ?,
                    ?,
                    'STARTED',
                    'AMBIGUOUS',
                    'SAME_TARGET_RETRY_FORBIDDEN',
                    'FALLBACK_FORBIDDEN',
                    ?
                )
                """,
                UUID.randomUUID(),
                planId,
                1,
                UUID.randomUUID(),
                MODEL,
                timestamp(physicalAttemptAt.plusSeconds(1)),
                timestamp(physicalAttemptAt.plusSeconds(1))
        );
    }

    private int countAttempts() {
        return java.util.Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from public.model_execution_attempts
                        where execution_plan_id = ?
                        """,
                        Integer.class,
                        planId
                ),
                "PostgreSQL count(*) must not be null"
        );
    }

    private int countPreparedBindings() {
        return java.util.Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from public.model_route_prepared_requests_v62
                        where model_route_decision_id = ?
                        """,
                        Integer.class,
                        decisionId
                ),
                "PostgreSQL count(*) must not be null"
        );
    }

    private static void assertCheckViolation(
            ThrowableAssert.ThrowingCallable operation
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(DataAccessException.class)
                .satisfies(error -> {
                    SQLException sql =
                            ModelIntegrationTestSupport.rootSqlException(error);

                    assertThat(sql.getSQLState())
                            .isEqualTo(CHECK_VIOLATION_SQLSTATE);
                });
    }

    private static void assertCheckRejection(
            ThrowableAssert.ThrowingCallable operation,
            String expectedMessage
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(DataAccessException.class)
                .satisfies(error -> {
                    SQLException sql =
                            ModelIntegrationTestSupport.rootSqlException(error);

                    assertThat(sql.getSQLState())
                            .isEqualTo(CHECK_VIOLATION_SQLSTATE);
                    assertThat(sql.getMessage())
                            .contains(expectedMessage);
                });
    }

}
