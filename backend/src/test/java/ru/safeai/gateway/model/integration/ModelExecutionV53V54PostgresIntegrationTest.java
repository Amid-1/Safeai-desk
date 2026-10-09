package ru.safeai.gateway.model.integration;

import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.integration.AbstractChatPostgresIntegrationTest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCapability;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the physical execution fence from V52 through V65,
 * including multi-connection attempt sequencing races.
 *
 * <p>V65 does not weaken the older sequencing tests: this fixture creates the
 * durable V64 final-request binding required by every legitimate physical
 * attempt. Absence of that binding is tested separately by
 * {@link ModelExecutionPreparedBindingV65PostgresIntegrationTest}.</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.usage.rollup.enabled=false",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelExecutionV53V54PostgresIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    private static final Instant START = ModelTestFixtures.NOW;
    private static final String MODEL = "gpt-test";

    private UUID decisionId;
    private UUID turnId;
    private UUID planId;
    private UUID operationId;

    @BeforeEach
    void arrangeGovernedProcessingTurnAndPlan() {
        String catalogModelKey =
                "openai:" + MODEL + ":" + UUID.randomUUID();

        ModelCatalogEntry template = ModelTestFixtures.freeEntry();
        ModelCatalogEntry entry = new ModelCatalogEntry(
                UUID.randomUUID(),
                catalogModelKey,
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
        UUID clientId = UUID.randomUUID();
        operationId = UUID.randomUUID();
        UUID messageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientId,
                "hello",
                START.minusSeconds(20)
        );

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> {
                    decisionId = routing.decide(
                            new ModelRouteRequest(
                                    ORGANIZATION_ID,
                                    USER_ID,
                                    CHAT_ID,
                                    turnId,
                                    clientId,
                                    ModelTestFixtures.REQUEST_HASH,
                                    catalogModelKey,
                                    "hello",
                                    List.of(),
                                    Set.of(),
                                    0L
                            ),
                            principal
                    ).decisionId();

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
                            clientId,
                            ModelTestFixtures.REQUEST_HASH,
                            operationId,
                            messageId,
                            UUID.randomUUID(),
                            databaseLeaseUntil(),
                            timestamp(START),
                            MODEL,
                            decisionId,
                            timestamp(START.minusSeconds(10)),
                            timestamp(START.minusSeconds(10))
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

                    ModelIntegrationTestSupport.insertGeneralPreparedBindingV64(
                            jdbcTemplate,
                            decisionId,
                            turnId,
                            operationId,
                            ModelIntegrationTestSupport.BASE_REQUEST_SHA256,
                            ModelIntegrationTestSupport.PREPARED_REQUEST_SHA256,
                            START
                    );

                    planId = UUID.randomUUID();
                    insertPlan();
                });

        assertThat(decisionId).isNotNull();
    }

    @Test
    void firstStartedAttemptIsDurableAndSecondIsRejectedWhileItRemainsOpen() {
        insertAttempt(1, MODEL, "static-v1");
        assertThatThrownBy(() -> insertAttempt(2, MODEL, "static-v1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countAttempts()).isEqualTo(1);
    }

    @Test
    void ambiguousAndSucceededPreviousAttemptsNeverAuthorizeNextCall() {
        insertAttempt(1, MODEL, "static-v1");
        terminal("AMBIGUOUS", "AMBIGUOUS", "SAME_TARGET_RETRY_FORBIDDEN");
        assertThatThrownBy(() -> insertAttempt(2, MODEL, "static-v1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countAttempts()).isEqualTo(1);
    }

    @Test
    void succeededPhysicalAttemptPreventsReplayEvenIfChatFinalizationNeverCommits() {
        insertAttempt(1, MODEL, "static-v1");
        jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome='SUCCEEDED', outcome_certainty='KNOWN_EXECUTED',
                    retry_safety='SAME_TARGET_RETRY_FORBIDDEN',
                    response_observed=true,
                    response_validation_status='ACCEPTED',
                    resolved_physical_model=?, finished_at=?,
                    usage_status='MISSING', specialized_dimensions_present=false,
                    specialized_dimensions_valid=true, pricing_status='UNPRICED'
                where execution_plan_id=?
                """,
                MODEL,
                timestamp(START.plusSeconds(1)),
                planId
        );
        assertThatThrownBy(() -> insertAttempt(2, MODEL, "static-v1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                """
                select reconciliation_reason
                from public.model_execution_reconciliation_queue_v54
                where execution_plan_id=?
                """,
                String.class,
                planId
        )).isEqualTo("PROVIDER_SUCCEEDED_CHAT_NOT_SUCCEEDED");
    }

    @Test
    void retryForbiddenFailedAttemptCannotBeFollowed() {
        insertAttempt(1, MODEL, "static-v1");
        terminal("FAILED", "KNOWN_REJECTED", "SAME_TARGET_RETRY_FORBIDDEN");
        assertThatThrownBy(() -> insertAttempt(2, MODEL, "static-v1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyContiguousExactSameTargetIsAllowedAfterKnownRetryableRejection() {
        insertAttempt(1, MODEL, "static-v1");
        terminal("FAILED", "KNOWN_REJECTED", "SAME_TARGET_RETRY_ALLOWED");

        assertTriggerRejection(
                () -> insertAttempt(3, MODEL, "static-v1"),
                "V50 execution attempts must be sequential"
        );
        assertGovernanceRejection(
                () -> insertAttempt(2, MODEL, "static-v2")
        );
        assertThat(countAttempts()).isEqualTo(1);

        insertAttempt(2, MODEL, "static-v1");
        assertThat(countAttempts()).isEqualTo(2);
    }

    @Test
    void routeRejectsWrongProviderAndWrongPhysicalModelBeforeAnyPhysicalCall() {
        assertThatThrownBy(() ->
                insertAttempt(1, "gpt-unapproved", "static-v1")
        ).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.model_execution_attempts (
                    id, execution_plan_id, attempt_number, provider_attempt_id,
                    provider_type, provider_configuration_ref,
                    provider_configuration_version,
                    deployment_ref, deployment_version,
                    requested_physical_model,
                    started_at, outcome, outcome_certainty,
                    retry_safety, fallback_safety, created_at
                ) values (
                    ?, ?, 1, ?, 'anthropic', ?, 'static-v1',
                    ?, 'static-v1', ?, ?,
                    'STARTED', 'AMBIGUOUS',
                    'SAME_TARGET_RETRY_FORBIDDEN',
                    'FALLBACK_FORBIDDEN', ?
                )
                """,
                UUID.randomUUID(),
                planId,
                UUID.randomUUID(),
                "static:anthropic",
                "static:anthropic:gpt-test",
                MODEL,
                timestamp(START),
                timestamp(START)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countAttempts()).isZero();
    }

    @Test
    void immutableStartedIdentityAndTerminalEvidenceCannotBeChangedOrDeleted() {
        insertAttempt(1, MODEL, "static-v1");

        assertTriggerRejection(
                () -> jdbcTemplate.update(
                        """
                        update public.model_execution_attempts
                        set outcome='FAILED', outcome_certainty='KNOWN_REJECTED',
                            retry_safety='SAME_TARGET_RETRY_ALLOWED',
                            failure_type='RATE_LIMITED', finished_at=?,
                            requested_physical_model='another'
                        where execution_plan_id=?
                        """,
                        timestamp(START.plusSeconds(2)),
                        planId
                ),
                "V51 execution attempt identity is immutable"
        );

        assertThat(jdbcTemplate.queryForObject(
                """
                select requested_physical_model
                from public.model_execution_attempts
                where execution_plan_id=?
                """,
                String.class,
                planId
        )).isEqualTo(MODEL);
        assertThat(jdbcTemplate.queryForObject(
                """
                select outcome
                from public.model_execution_attempts
                where execution_plan_id=?
                """,
                String.class,
                planId
        )).isEqualTo("STARTED");

        terminal("FAILED", "KNOWN_REJECTED", "SAME_TARGET_RETRY_ALLOWED");
        assertTriggerRejection(
                () -> jdbcTemplate.update(
                        """
                        update public.model_execution_attempts
                        set failure_type='TAMPER'
                        where execution_plan_id=?
                        """,
                        planId
                ),
                "V51 terminal execution attempts are immutable"
        );
        assertTriggerRejection(
                () -> jdbcTemplate.update(
                        "delete from public.model_execution_attempts where execution_plan_id=?",
                        planId
                ),
                "V51 execution attempts cannot be deleted"
        );
        assertThat(jdbcTemplate.queryForObject(
                """
                select failure_type
                from public.model_execution_attempts
                where execution_plan_id=?
                """,
                String.class,
                planId
        )).isEqualTo("RATE_LIMITED");
    }

    @Test
    void nullPricedFreeAndInconsistentUsageCannotBypassSqlThreeValuedLogic() {
        insertAttempt(1, MODEL, "static-v1");

        String terminalIdentity = "outcome='SUCCEEDED', "
                + "outcome_certainty='KNOWN_EXECUTED', "
                + "resolved_physical_model='gpt-test', "
                + "response_observed=true, "
                + "response_validation_status='ACCEPTED', "
                + "finished_at='2026-08-28 12:00:04+00'";

        for (String invalidEvidence : new String[]{
                "usage_status='AVAILABLE', input_tokens=1, output_tokens=1, "
                        + "specialized_dimensions_present=null, "
                        + "specialized_dimensions_valid=true, pricing_status='UNPRICED'",
                "usage_status='MISSING', specialized_dimensions_present=false, "
                        + "specialized_dimensions_valid=true, pricing_status='FREE', "
                        + "cost_usd=null, currency=null, price_book_version=null",
                "usage_status='AVAILABLE', input_tokens=1, output_tokens=1, "
                        + "specialized_dimensions_present=false, "
                        + "specialized_dimensions_valid=true, pricing_status='PRICED', "
                        + "cost_usd=0, currency='USD', price_book_version='v1'",
                "usage_status='AVAILABLE', input_tokens=1, output_tokens=1, "
                        + "specialized_dimensions_present=false, "
                        + "specialized_dimensions_valid=true, pricing_status='PRICED', "
                        + "cost_usd=null, currency='USD', price_book_version='v1'",
                "usage_status='AVAILABLE', input_tokens=1, output_tokens=1, "
                        + "specialized_dimensions_present=false, "
                        + "specialized_dimensions_valid=null, pricing_status='UNPRICED'"
        }) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update public.model_execution_attempts set "
                            + terminalIdentity + ", "
                            + invalidEvidence
                            + " where execution_plan_id=?",
                    planId
            ))
                    .as(invalidEvidence)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(failure -> {
                        SQLException sql =
                                ModelIntegrationTestSupport.rootSqlException(failure);
                        assertThat(sql.getSQLState()).isEqualTo("23514");
                    });
        }

        assertThat(countAttempts()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select outcome from public.model_execution_attempts where execution_plan_id=?",
                String.class,
                planId
        )).isEqualTo("STARTED");
    }

    @Test
    void validFreeZeroAndIncompleteSpecializedEvidenceRemainRepresentable() {
        insertAttempt(1, MODEL, "static-v1");
        jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome='SUCCEEDED', outcome_certainty='KNOWN_EXECUTED',
                    response_observed=true,
                    response_validation_status='ACCEPTED',
                    resolved_physical_model=?, finished_at=?,
                    usage_status='AVAILABLE', input_tokens=0, output_tokens=0,
                    specialized_dimensions_present=false,
                    specialized_dimensions_valid=true,
                    pricing_status='FREE', cost_usd=0, currency='USD',
                    price_book_version='cat-v1-free'
                where execution_plan_id=? and attempt_number=1
                """,
                MODEL,
                timestamp(START.plusSeconds(2)),
                planId
        );
        assertThat(jdbcTemplate.queryForObject(
                """
                select pricing_status
                from public.model_execution_attempts
                where execution_plan_id=?
                """,
                String.class,
                planId
        )).isEqualTo("FREE");
        assertThat(jdbcTemplate.queryForObject(
                """
                select cost_usd
                from public.model_execution_attempts
                where execution_plan_id=?
                """,
                BigDecimal.class,
                planId
        )).isEqualByComparingTo("0");
    }

    @Test
    void partialUsageWithInvalidSpecializedDimensionIsPreservedAsUnknownPrice() {
        insertAttempt(1, MODEL, "static-v1");
        jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome='SUCCEEDED', outcome_certainty='KNOWN_EXECUTED',
                    response_observed=true,
                    response_validation_status='ACCEPTED',
                    resolved_physical_model=?, finished_at=?,
                    usage_status='PARTIAL', input_tokens=100, output_tokens=null,
                    specialized_dimensions_present=true,
                    specialized_dimensions_valid=false,
                    pricing_status='CALCULATION_FAILED'
                where execution_plan_id=? and attempt_number=1
                """,
                MODEL,
                timestamp(START.plusSeconds(2)),
                planId
        );
        assertThat(jdbcTemplate.queryForObject(
                """
                select pricing_status
                from public.model_execution_attempts
                where execution_plan_id=?
                """,
                String.class,
                planId
        )).isEqualTo("CALCULATION_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                """
                select reconciliation_reason
                from public.model_execution_reconciliation_queue_v54
                where execution_plan_id=?
                """,
                String.class,
                planId
        )).isEqualTo("PROVIDER_SUCCEEDED_CHAT_NOT_SUCCEEDED");
    }

    @Test
    void billedFailureAndSuccessBothContributeToMonthlyPhysicalCost() {
        insertAttempt(1, MODEL, "static-v1");
        jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome='FAILED', outcome_certainty='KNOWN_REJECTED',
                    retry_safety='SAME_TARGET_RETRY_ALLOWED',
                    failure_type='RATE_LIMITED', finished_at=?,
                    usage_status='AVAILABLE', input_tokens=100, output_tokens=200,
                    specialized_dimensions_present=false,
                    specialized_dimensions_valid=true,
                    pricing_status='PRICED', cost_usd=0.125,
                    currency='USD', price_book_version='cat-v1-billed-failure'
                where execution_plan_id=? and attempt_number=1
                """,
                timestamp(START.plusSeconds(2)),
                planId
        );

        insertAttempt(2, MODEL, "static-v1");
        jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome='SUCCEEDED', outcome_certainty='KNOWN_EXECUTED',
                    response_observed=true,
                    response_validation_status='ACCEPTED',
                    resolved_physical_model=?, finished_at=?,
                    usage_status='AVAILABLE', input_tokens=200, output_tokens=300,
                    specialized_dimensions_present=false,
                    specialized_dimensions_valid=true,
                    pricing_status='PRICED', cost_usd=0.375,
                    currency='USD', price_book_version='cat-v1-billed-success'
                where execution_plan_id=? and attempt_number=2
                """,
                MODEL,
                timestamp(START.plusSeconds(4)),
                planId
        );

        var snapshot = new ModelRouteDecisionRepository(jdbcTemplate)
                .loadCommittedMonthlyCostSnapshot(
                        ORGANIZATION_ID,
                        START.minusSeconds(86_400),
                        START.plusSeconds(86_400)
                );
        assertThat(snapshot.committedCostUsd())
                .isEqualByComparingTo("0.500000000000");
        assertThat(snapshot.actualCostUsd())
                .isEqualByComparingTo("0.500000000000");
        assertThat(snapshot.reservedCostUsd()).isZero();
        assertThat(snapshot.unknownCommittedCostCount()).isZero();
    }

    @Test
    void unknownAmbiguousPhysicalCostIsNeverMisrepresentedAsComplete() {
        insertAttempt(1, MODEL, "static-v1");
        terminal("AMBIGUOUS", "AMBIGUOUS", "SAME_TARGET_RETRY_FORBIDDEN");

        var snapshot = new ModelRouteDecisionRepository(jdbcTemplate)
                .loadCommittedMonthlyCostSnapshot(
                        ORGANIZATION_ID,
                        START.minusSeconds(86_400),
                        START.plusSeconds(86_400)
                );
        assertThat(snapshot.actualCostUsd()).isZero();
        assertThat(snapshot.reservedCostUsd()).isZero();
        assertThat(snapshot.unknownCommittedCostCount()).isGreaterThan(0);
    }

    @Test
    void v52RejectsCatalogVisionAndImageMismatchAtDatabaseBoundary() {
        ModelCatalogEntry template = ModelTestFixtures.freeEntry();
        ModelCatalogEntry invalid = new ModelCatalogEntry(
                UUID.randomUUID(),
                "test:vision-mismatch:" + UUID.randomUUID(),
                1,
                "openai",
                MODEL,
                MODEL,
                template.lifecycle(),
                template.maxInputTokens(),
                template.maxOutputTokens(),
                Set.of(ModelCapability.VISION),
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
                START.minusSeconds(1),
                template.source(),
                USER_ID,
                START.minusSeconds(2)
        );

        assertThatThrownBy(() ->
                new ModelCatalogRepository(jdbcTemplate).insert(invalid, "FLAT_TOKEN_V1")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v53RejectsFreeCatalogWithSqlNullCorePrices() {
        ModelCatalogEntry base = ModelTestFixtures.freeEntry();
        ModelCatalogEntry invalid = new ModelCatalogEntry(
                UUID.randomUUID(),
                "test:null-free:" + UUID.randomUUID(),
                1,
                "openai",
                MODEL,
                MODEL,
                base.lifecycle(),
                base.maxInputTokens(),
                base.maxOutputTokens(),
                base.capabilities(),
                base.inputModalities(),
                base.outputModalities(),
                base.retentionStatus(),
                base.retentionDays(),
                base.trainingUseStatus(),
                base.pricingStatus(),
                base.pricingComplete(),
                null,
                base.cachedInputUsdPer1mTokens(),
                base.cacheWriteInputUsdPer1mTokens(),
                null,
                base.extraPricingJson(),
                base.pricingVersion(),
                START.minusSeconds(1),
                base.source(),
                USER_ID,
                START.minusSeconds(2)
        );

        assertThatThrownBy(() ->
                new ModelCatalogRepository(jdbcTemplate).insert(invalid, "FLAT_TOKEN_V1")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectedRequestedModelDoesNotCreateSecondPlan() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.model_execution_plans (
                    id, provider_operation_id, chat_turn_id, organization_id,
                    model_route_decision_id, requested_model, created_at
                ) values (?, ?, ?, ?, ?, 'wrong-physical-model', ?)
                """,
                UUID.randomUUID(),
                operationId,
                turnId,
                ORGANIZATION_ID,
                decisionId,
                timestamp(START)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.model_execution_plans
                where chat_turn_id=?
                """,
                Integer.class,
                turnId
        )).isEqualTo(1);
    }

    @Test
    void terminalChatTurnPreventsAnyAdditionalPhysicalAttempt() {
        insertAttempt(1, MODEL, "static-v1");
        terminal("FAILED", "KNOWN_REJECTED", "SAME_TARGET_RETRY_ALLOWED");
        jdbcTemplate.update(
                """
                update public.chat_turns
                set state='AMBIGUOUS', outcome_ambiguous=true,
                    failure_code='TEST', processing_token=null,
                    lease_until=null, completed_at=?, updated_at=?
                where id=?
                """,
                timestamp(START.plusSeconds(2)),
                timestamp(START.plusSeconds(2)),
                turnId
        );
        assertThatThrownBy(() -> insertAttempt(2, MODEL, "static-v1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoConcurrentWritersCannotBothStartAttemptTwo() throws Exception {
        insertAttempt(1, MODEL, "static-v1");
        terminal("FAILED", "KNOWN_REJECTED", "SAME_TARGET_RETRY_ALLOWED");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> raceAttempt(ready, go));
            var second = executor.submit(() -> raceAttempt(ready, go));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int successes =
                    (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                            + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(successes).isEqualTo(1);
            assertThat(countAttempts()).isEqualTo(2);
        }
    }

    private boolean raceAttempt(
            CountDownLatch ready,
            CountDownLatch go
    ) throws Exception {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Race not released");
        }
        try {
            insertAttempt(2, MODEL, "static-v1");
            return true;
        } catch (DataAccessException expected) {
            SQLException sql =
                    ModelIntegrationTestSupport.rootSqlException(expected);
            assertThat(sql.getSQLState())
                    .as("The losing writer must be rejected by V50 sequencing or a unique constraint")
                    .satisfies(state -> {
                        if ("P0001".equals(state)) {
                            assertThat(sql.getMessage())
                                    .contains("V50 execution attempts must be sequential");
                        } else {
                            assertThat(state).isEqualTo("23505");
                        }
                    });
            return false;
        }
    }

    /**
     * V67 rejects any physical attempt whose owning PROCESSING turn has an
     * expired fencing lease. START is deliberately historical (reproducible
     * routing/pricing evidence), so it must NOT be used to calculate a live
     * lease for a test running months later.
     *
     * Use PostgreSQL clock on the same test database as the V67 trigger.
     * This does not bypass the guard or manipulate database time.
     */
    private java.sql.Timestamp databaseLeaseUntil() {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        "select clock_timestamp() + interval '10 minutes'",
                        java.sql.Timestamp.class
                ),
                "PostgreSQL must return a live test lease timestamp"
        );
    }

    private void insertPlan() {
        jdbcTemplate.update(
                """
                insert into public.model_execution_plans (
                    id, provider_operation_id, chat_turn_id, organization_id,
                    model_route_decision_id, requested_model, created_at
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
    }

    private void insertAttempt(
            int number,
            String model,
            String configVersion
    ) {
        jdbcTemplate.update(
                """
                insert into public.model_execution_attempts (
                    id, execution_plan_id, attempt_number, provider_attempt_id,
                    provider_type, provider_configuration_ref,
                    provider_configuration_version,
                    deployment_ref, deployment_version,
                    requested_physical_model,
                    started_at, outcome, outcome_certainty,
                    retry_safety, fallback_safety, created_at
                ) values (
                    ?, ?, ?, ?, 'openai', 'static:openai', ?,
                    'static:openai:gpt-test', 'static-v1', ?, ?,
                    'STARTED', 'AMBIGUOUS',
                    'SAME_TARGET_RETRY_FORBIDDEN',
                    'FALLBACK_FORBIDDEN', ?
                )
                """,
                UUID.randomUUID(),
                planId,
                number,
                UUID.randomUUID(),
                configVersion,
                model,
                timestamp(START.plusSeconds(number)),
                timestamp(START.plusSeconds(number))
        );
    }

    private void terminal(
            String outcome,
            String certainty,
            String retry
    ) {
        jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome=?, outcome_certainty=?, retry_safety=?,
                    failure_type='RATE_LIMITED', finished_at=?
                where execution_plan_id=? and attempt_number=1
                """,
                outcome,
                certainty,
                retry,
                timestamp(START.plusSeconds(2)),
                planId
        );
    }

    private static void assertTriggerRejection(
            ThrowableAssert.ThrowingCallable operation,
            String expectedMessage
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(DataAccessException.class)
                .satisfies(error -> {
                    SQLException sql =
                            ModelIntegrationTestSupport.rootSqlException(error);
                    assertThat(sql.getSQLState()).isEqualTo("P0001");
                    assertThat(sql.getMessage()).contains(expectedMessage);
                });
    }

    private static void assertGovernanceRejection(
            ThrowableAssert.ThrowingCallable operation
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(DataAccessException.class)
                .satisfies(error -> {
                    SQLException sql =
                            ModelIntegrationTestSupport.rootSqlException(error);
                    assertThat(sql.getSQLState())
                            .matches("P0001|23[0-9A-Z]{3}");
                });
    }

    private int countAttempts() {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        """
                        select count(*)
                        from public.model_execution_attempts
                        where execution_plan_id=?
                        """,
                        Integer.class,
                        planId
                ),
                "PostgreSQL count(*) must not be null"
        );
    }
}
