package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.execution.AiExecutionAttemptRecorder;
import ru.safeai.gateway.ai.execution.AiExecutionPlanService;
import ru.safeai.gateway.ai.execution.AiExecutionRequest;
import ru.safeai.gateway.ai.execution.AiExecutionService;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.chat.entity.ChatTurnState;
import ru.safeai.gateway.chat.exception.AiOutcomeAmbiguousException;
import ru.safeai.gateway.chat.exception.ChatTurnFailedException;
import ru.safeai.gateway.chat.exception.IdempotencyKeyReusedException;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.chat.service.ChatTurnFinalizationService;
import ru.safeai.gateway.chat.service.ChatTurnReservationService;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelCatalogSource;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.service.ModelRouteExecutionBindingService;
import ru.safeai.gateway.model.service.ModelRouteExecutionIdentity;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "safeai.chat.recovery.enabled=false",
        "safeai.chat.quota.enabled=true",
        "safeai.rate-limit.ai-messages.enabled=false"
})
@ActiveProfiles("test")
@Import(ChatIntegrationClockConfiguration.class)
@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
class ChatTurnStateMachineIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    private static final String TEST_MODEL_KEY =
            "test:chat-state-machine:mock-safeai";

    private static final UUID TEST_POLICY_ID =
            UUID.fromString(
                    "93939393-9393-4939-8939-939393939393"
            );

    private static final int TEST_POLICY_VERSION = 1;

    @Autowired
    private ChatTurnReservationService reservationService;

    @Autowired
    private ChatTurnFinalizationService finalizationService;

    @Autowired
    private AiExecutionService aiExecutionService;

    @Autowired
    private ModelRouteExecutionBindingService executionBindingService;

    @Autowired
    private AiExecutionPlanService executionPlanService;

    @Autowired
    private AiExecutionAttemptRecorder physicalAttemptRecorder;

    @Autowired
    private ChatTurnRepository turnRepository;

    @Autowired
    private ModelCatalogRepository modelCatalogRepository;

    @Autowired
    private OrganizationModelPolicyRepository policyRepository;

    @Autowired
    private RuntimeModelStatusService runtimeModelStatusService;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void preparePrimaryChat() {
        adjustableClock().reset();
        resetModelControlPlaneFixture();
        alignChatTimestamps();
        assertOwnedChatVisible();
        seedEffectiveMockRuntimeCatalog();
        seedEnabledOrganizationPolicy();
        assertGovernedRuntimeReady();
        alignTestClockWithDatabaseForPhysicalExecution();
    }

    @AfterEach
    void clearModelControlPlaneFixture() {
        adjustableClock().reset();
        resetModelControlPlaneFixture();
    }

    /**
     * The PostgreSQL container is shared by the integration-test JVM, while
     * model catalog and policy rows are append-only production evidence.
     *
     * <p>These state-machine tests are not routing-policy tests, therefore each
     * method gets one isolated governed runtime snapshot and one exact tenant
     * policy. V65 intentionally rejects a new integrity-v3 executable route
     * when policy_id is absent, so clearing only model_catalog_entries is no
     * longer sufficient.</p>
     *
     * <p>TRUNCATE is used only against the isolated Testcontainers database.
     * No production immutability, FK, trigger or V65 execution fence is
     * weakened.</p>
     */
    private void resetModelControlPlaneFixture() {
        jdbcTemplate.execute(
                """
                truncate table
                    public.model_route_decisions,
                    public.organization_model_policies,
                    public.model_catalog_entries
                cascade
                """
        );
    }

    /**
     * The strict router must resolve exactly one effective logical catalog
     * snapshot for the configured mock/mock-safeai physical runtime.
     */
    private void seedEffectiveMockRuntimeCatalog() {
        RuntimeModelStatusResponse runtime = runtimeModelStatusService.current();

        assertThat(runtime.provider()).isEqualTo("mock");
        assertThat(runtime.model()).isEqualTo("mock-safeai");

        Instant now = clock.instant();
        ModelCatalogEntry catalogEntry = new ModelCatalogEntry(
                UUID.randomUUID(),
                TEST_MODEL_KEY,
                1,
                runtime.provider(),
                runtime.model(),
                "Chat state-machine mock runtime",
                ModelLifecycle.ACTIVE,
                runtime.maxInputTokens(),
                runtime.maxOutputTokens(),
                Set.of(),
                Set.of(ModelModality.TEXT),
                Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED,
                null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.FREE,
                true,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                "{}",
                null,
                now.minusSeconds(60),
                ModelCatalogSource.MANUAL,
                USER_ID,
                now.minusSeconds(120)
        );

        modelCatalogRepository.insert(catalogEntry, "FLAT_TOKEN_V1");

        assertThat(modelCatalogRepository.findEffectiveByRuntime(
                runtime.provider(),
                runtime.model(),
                now
        ))
                .extracting(ModelCatalogEntry::id)
                .containsExactly(catalogEntry.id());
    }

    /**
     * V65 requires every new executable integrity-v3 route to bind an exact
     * immutable organization policy. The policy intentionally allows only the
     * logical model owned by this test and makes it the deterministic default.
     */
    private void seedEnabledOrganizationPolicy() {
        Instant now = clock.instant();

        OrganizationModelPolicy policy =
                new OrganizationModelPolicy(
                        TEST_POLICY_ID,
                        ORGANIZATION_ID,
                        TEST_POLICY_VERSION,
                        true,
                        Set.of(TEST_MODEL_KEY),
                        Set.of(),
                        TEST_MODEL_KEY,
                        null,
                        null,
                        null,
                        null,
                        BudgetEnforcement.SOFT,
                        false,
                        false,
                        false,
                        USER_ID,
                        now.minusSeconds(30)
                );

        policyRepository.insert(policy);
    }

    /**
     * Fail in fixture setup, rather than later as MODEL_NOT_ALLOWED inside an
     * unrelated state-machine assertion, if Model Control Plane prerequisites
     * drift again.
     */
    private void assertGovernedRuntimeReady() {
        RuntimeModelStatusResponse runtime =
                runtimeModelStatusService.current();

        assertThat(
                modelCatalogRepository.findEffectiveByRuntime(
                        runtime.provider(),
                        runtime.model(),
                        clock.instant()
                )
        )
                .as(
                        "State-machine tests require exactly one effective "
                                + "catalog mapping for the mock Runtime"
                )
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.modelKey())
                            .isEqualTo(TEST_MODEL_KEY);

                    assertThat(entry.lifecycle())
                            .isEqualTo(ModelLifecycle.ACTIVE);
                });

        assertThat(
                policyRepository.findLatest(
                        ORGANIZATION_ID
                )
        )
                .as(
                        "V65 requires an enabled immutable organization policy "
                                + "before a new executable route can be created"
                )
                .hasValueSatisfying(policy -> {
                    assertThat(policy.id())
                            .isEqualTo(TEST_POLICY_ID);

                    assertThat(policy.version())
                            .isEqualTo(TEST_POLICY_VERSION);

                    assertThat(policy.enabled())
                            .isTrue();

                    assertThat(policy.allowModelKeys())
                            .containsExactly(TEST_MODEL_KEY);

                    assertThat(policy.denyModelKeys())
                            .isEmpty();

                    assertThat(policy.defaultModelKey())
                            .isEqualTo(TEST_MODEL_KEY);
                });
    }

    @Test
    void newTurnCommitsProviderOperationIdAndQuotaBeforeProviderCall() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        var turn = turnRepository
                .findById(context.turnId())
                .orElseThrow();

        assertThat(turn.getProviderOperationId())
                .isEqualTo(context.providerOperationId())
                .isEqualTo(
                        context.aiRequest().providerOperationId()
                );

        assertThat(turn.getState())
                .isEqualTo(ChatTurnState.PROCESSING);

        assertThat(turn.getProviderCallStartedAt())
                .isNull();

        assertAllowedRouteBoundToTestPolicy(
                context
        );

        assertThat(countChatTurns())
                .isEqualTo(1);

        assertThat(countChatMessages())
                .isEqualTo(1);

        assertThat(countChatQuotaReservations())
                .isEqualTo(1);
    }

    @Test
    void sameIdAndSameContentReplaysSinglePersistedResult() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        UUID attemptId = startPhysicalAttempt(context);
        AiChatResponse providerResponse = ChatTestFixtures.freeResponse();
        physicalAttemptRecorder.succeeded(attemptId, providerResponse);

        finalizationService.succeed(
                context,
                providerResponse,
                principal()
        );

        ChatProcessingContext replay =
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                );

        assertThat(replay.replay())
                .isTrue();

        assertThat(replay.turnId())
                .isEqualTo(context.turnId());

        assertThat(replay.aiRequest())
                .isNull();

        assertThat(countChatTurns())
                .isEqualTo(1);

        assertThat(countChatMessages())
                .isEqualTo(2);

        assertThat(countChatQuotaReservations())
                .isEqualTo(1);
    }

    @Test
    void sameIdWithDifferentNormalizedContentIsConflict() {
        UUID clientRequestId = UUID.randomUUID();

        reserve(
                "First question",
                clientRequestId
        );

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Different question",
                                clientRequestId
                        ),
                        principal()
                )
        )
                .isInstanceOf(
                        IdempotencyKeyReusedException.class
                )
                .extracting("code")
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(countChatTurns())
                .isEqualTo(1);

        assertThat(countChatMessages())
                .isEqualTo(1);
    }

    @Test
    void unambiguousFailureIsStableAndNeverReopensProviderOperation() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        RouteIdentity routeIdentity =
                loadRouteIdentity(context);

        UUID attemptId = startPhysicalAttempt(context);
        AiProviderException providerFailure = unambiguousProviderFailure();
        physicalAttemptRecorder.failed(attemptId, providerFailure);

        finalizationService.failUnambiguous(
                context,
                providerFailure,
                principal()
        );

        assertRouteIdentityUnchanged(
                context,
                routeIdentity
        );

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                )
        )
                .isInstanceOf(
                        ChatTurnFailedException.class
                )
                .extracting("code")
                .isEqualTo("AI_PROVIDER_INVALID_REQUEST");

        var turn = turnRepository
                .findById(context.turnId())
                .orElseThrow();

        assertThat(turn.getState())
                .isEqualTo(ChatTurnState.FAILED);

        assertThat(turn.isOutcomeAmbiguous())
                .isFalse();

        assertThat(quotaState(context.turnId()))
                .isEqualTo("RELEASED");
    }

    @Test
    void ambiguousProviderOutcomeIsStableAndCannotAutoRetry() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        RouteIdentity routeIdentity =
                loadRouteIdentity(context);

        UUID attemptId = startPhysicalAttempt(context);
        physicalAttemptRecorder.ambiguous(
                attemptId,
                new IllegalStateException("Simulated uncertain provider outcome")
        );

        finalizationService.markAmbiguous(
                context,
                "openai",
                "requested-model",
                "provider-request-id",
                AiProviderErrorType.TIMEOUT.name(),
                "AI_PROVIDER_OUTCOME_AMBIGUOUS",
                principal()
        );

        assertRouteIdentityUnchanged(
                context,
                routeIdentity
        );

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                )
        ).isInstanceOf(
                AiOutcomeAmbiguousException.class
        );

        var turn = turnRepository
                .findById(context.turnId())
                .orElseThrow();

        assertThat(turn.getState())
                .isEqualTo(ChatTurnState.AMBIGUOUS);

        assertThat(turn.getProviderRequestId())
                .isEqualTo("provider-request-id");

        assertThat(turn.isOutcomeAmbiguous())
                .isTrue();

        assertThat(quotaState(context.turnId()))
                .isEqualTo("AMBIGUOUS");
    }

    @Test
    void staleProcessingBeforeProviderCallBecomesRecoverableFailed() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        expire(context.turnId());

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                )
        )
                .isInstanceOf(
                        ChatTurnFailedException.class
                )
                .extracting("code")
                .isEqualTo(
                        "STALE_BEFORE_PROVIDER_CALL"
                );

        assertThat(turnState(context.turnId()))
                .isEqualTo("FAILED");

        assertThat(quotaState(context.turnId()))
                .isEqualTo("RELEASED");
    }

    @Test
    void staleProcessingAfterProviderCallBecomesAmbiguousNotBusyForever() {
        UUID clientRequestId = UUID.randomUUID();

        ChatProcessingContext context = reserve(
                "Question",
                clientRequestId
        );

        startPhysicalAttempt(context);

        expire(context.turnId());

        assertThatThrownBy(() ->
                reservationService.reserveOrReplay(
                        CHAT_ID,
                        new SendMessageRequest(
                                "Question",
                                clientRequestId
                        ),
                        principal()
                )
        ).isInstanceOf(
                AiOutcomeAmbiguousException.class
        );

        assertThat(turnState(context.turnId()))
                .isEqualTo("AMBIGUOUS");

        assertThat(quotaState(context.turnId()))
                .isEqualTo("AMBIGUOUS");
    }

    @Test
    void wrongFencingTokenCannotFinalizeTurn() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        startPhysicalAttempt(context);

        int updated =
                markSucceededWithWrongFencingToken(
                        context
                );

        assertThat(updated)
                .isZero();

        assertThat(turnState(context.turnId()))
                .isEqualTo("PROCESSING");

        assertThat(countChatMessages())
                .isEqualTo(1);
    }

    @Test
    void refusedResponseIsPersistedAsSuccessfulProviderOutcome() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        UUID attemptId = startPhysicalAttempt(context);
        AiChatResponse providerResponse = response(AiResponseStatus.REFUSED, "refused");
        physicalAttemptRecorder.succeeded(attemptId, providerResponse);

        var result = finalizationService.succeed(
                context,
                providerResponse,
                principal()
        );

        assertThat(
                result.assistantMessage()
                        .aiResponseStatus()
        ).isEqualTo("REFUSED");

        assertThat(result.state())
                .isEqualTo("SUCCEEDED");

        assertThat(turnState(context.turnId()))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void incompleteResponseIsPersistedWithUsageAndPricingMetadata() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        UUID attemptId = startPhysicalAttempt(context);
        AiChatResponse providerResponse = response(AiResponseStatus.INCOMPLETE, "max_output_tokens");
        physicalAttemptRecorder.succeeded(attemptId, providerResponse);

        var result = finalizationService.succeed(
                context,
                providerResponse,
                principal()
        );

        assertThat(
                result.assistantMessage()
                        .aiResponseStatus()
        ).isEqualTo("INCOMPLETE");

        assertThat(
                result.assistantMessage()
                        .inputTokens()
        ).isEqualTo(10);

        assertThat(
                result.assistantMessage()
                        .outputTokens()
        ).isEqualTo(20);

        assertThat(
                result.assistantMessage()
                        .pricingStatus()
        ).isEqualTo("FREE");
    }

    @Test
    void providerMetadataIsStoredWithoutMutatingV45RouteIdentity() {
        ChatProcessingContext context = reserve(
                "Question",
                UUID.randomUUID()
        );

        RouteIdentity routeIdentity =
                loadRouteIdentity(context);

        RouteIdentity decisionIdentity =
                loadDecisionRouteIdentity(
                        routeIdentity.modelRouteDecisionId()
                );

        assertThat(routeIdentity.provider())
                .isEqualTo(
                        decisionIdentity.provider()
                );

        assertThat(routeIdentity.requestedModel())
                .isEqualTo(
                        decisionIdentity.requestedModel()
                );

        UUID attemptId = startPhysicalAttempt(context);
        AiChatResponse providerResponse = response(AiResponseStatus.COMPLETED, "completed");
        physicalAttemptRecorder.succeeded(attemptId, providerResponse);

        finalizationService.succeed(
                context,
                providerResponse,
                principal()
        );

        var finalizedTurn = turnRepository
                .findById(context.turnId())
                .orElseThrow();

        String messageProviderRequestId =
                jdbcTemplate.queryForObject(
                        """
                        select provider_request_id
                        from chat_messages
                        where reply_to_message_id = ?
                        """,
                        String.class,
                        context.userMessageId()
                );

        assertThat(finalizedTurn.getProviderRequestId())
                .isEqualTo("provider-request-id");

        assertThat(messageProviderRequestId)
                .isEqualTo("provider-request-id");

        assertRouteIdentityUnchanged(
                context,
                routeIdentity
        );

        assertThat(finalizedTurn.getResolvedModel())
                .isEqualTo("resolved-model");
    }

    /**
     * Match test reservations to PostgreSQL wall time WITHOUT moving the
     * injected clock into the future.
     *
     * <p>PostgreSQL V67 records physical STARTED at clock_timestamp(). The
     * original V72 test helper advanced the JVM Clock one second AHEAD of
     * PostgreSQL; the resulting ChatTurn.created_at was greater than the
     * PostgreSQL provider_call_started_at, correctly violating the existing
     * chk_chat_turns_timestamps constraint.</p>
     *
     * <p>The two-second margin guarantees that a newly reserved ChatTurn is
     * older than the database's subsequent STARTED marker, without rewriting
     * timestamps or weakening triggers. The test clock is advanced again,
     * AFTER STARTED commits, before physical terminal evidence/finalization.</p>
     */
    private void alignTestClockWithDatabaseForPhysicalExecution() {
        Instant databaseTime = databaseNow();
        Instant reservationTime = databaseTime.minusSeconds(2);

        assertThat(reservationTime)
                .as("The fixed test fixture must precede PostgreSQL wall time")
                .isAfter(clock.instant());

        adjustableClock().advanceTo(reservationTime);

        assertThat(clock.instant())
                .as("ChatTurn reservations must not be dated after PostgreSQL")
                .isBefore(databaseNow());
    }

    /** PostgreSQL is authoritative for the V67 physical-attempt timestamp. */
    private Instant databaseNow() {
        Timestamp timestamp = Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        "SELECT clock_timestamp()",
                        Timestamp.class
                ),
                "PostgreSQL clock_timestamp() must not return null"
        );
        return timestamp.toInstant();
    }

    /**
     * Align finalization AFTER physical STARTED, not before it.
     *
     * <p>The recorder writes its physical STARTED evidence in a separate
     * committed transaction, and the V67 trigger stamps the ChatTurn using
     * PostgreSQL time. Advancing the test Clock to a subsequent DB reading
     * maintains completed_at >= provider_call_started_at for all successful,
     * failed and ambiguous terminal transitions.</p>
     */
    private void alignTestClockAfterPhysicalAttempt(Instant providerStartedAt) {
        Objects.requireNonNull(providerStartedAt, "providerStartedAt");

        Instant afterCommittedAttempt = databaseNow();
        assertThat(afterCommittedAttempt)
                .as("Physical STARTED must be committed before its finalization")
                .isAfterOrEqualTo(providerStartedAt);

        assertThat(afterCommittedAttempt)
                .as("The finalization clock must advance, not move backwards")
                .isAfter(clock.instant());

        adjustableClock().advanceTo(afterCommittedAttempt);
    }

    /**
     * Real V65/V67/V72 execution-boundary fixture. It creates the immutable
     * pre-RAG binding, sealed final request, execution plan and physical
     * STARTED attempt through production services. PostgreSQL then atomically
     * verifies the organization epoch, live ChatTurn lease and fencing token,
     * and is the ONLY writer of provider_call_started_at.
     * <p>
     * Returning the attempt ID allows each scenario to persist matching
     * physical terminal evidence (success, known failure or ambiguity).
     */
    private UUID startPhysicalAttempt(ChatProcessingContext context) {
        assertThat(context.replay()).isFalse();
        assertThat(context.aiRequest()).isNotNull();

        ModelRouteExecutionIdentity identity = (ModelRouteExecutionIdentity) aiExecutionService.bindExecution(
                context.modelRouteDecisionId(),
                context.turnId(),
                context.clientRequestId(),
                context.providerOperationId(),
                context.aiRequest(),
                context.knowledgeBaseId(),
                context.knowledgeMode()
        );

        ProviderExecutionTarget target = aiExecutionService.targetFor(
                context.requestedPhysicalModel()
        );

        executionBindingService.requireExecutable(
                identity,
                context.turnId(),
                context.modelRouteDecisionId(),
                context.aiRequest(),
                context.aiRequest(),
                target.providerType(),
                target.requestedPhysicalModel()
        );

        AiExecutionRequest executionRequest = new AiExecutionRequest(
                context.turnId(),
                context.modelRouteDecisionId(),
                context.aiRequest(),
                target,
                identity,
                context.aiRequest()
        );

        var plan = executionPlanService.createOrRead(executionRequest);
        UUID attemptId = UUID.randomUUID();
        physicalAttemptRecorder.started(
                plan.getId(),
                target,
                new AiProviderAttemptContext(
                        context.providerOperationId(),
                        attemptId,
                        1,
                        1
                )
        );

        var startedTurn = turnRepository.findById(context.turnId()).orElseThrow();
        Instant physicalStartedAt = Objects.requireNonNull(
                startedTurn.getProviderCallStartedAt(),
                "Only a committed physical STARTED may set the ChatTurn marker"
        );

        assertThat(physicalStartedAt)
                .as("PostgreSQL STARTED marker must not precede ChatTurn creation")
                .isAfterOrEqualTo(startedTurn.getCreatedAt());

        alignTestClockAfterPhysicalAttempt(physicalStartedAt);

        assertThat(clock.instant())
                .as("Terminal evidence must never be dated before physical STARTED")
                .isAfterOrEqualTo(physicalStartedAt);
        return attemptId;
    }

    private ChatProcessingContext reserve(
            String content,
            UUID clientRequestId
    ) {
        return reservationService.reserveOrReplay(
                CHAT_ID,
                new SendMessageRequest(
                        content,
                        clientRequestId
                ),
                principal()
        );
    }

    private int markSucceededWithWrongFencingToken(
            ChatProcessingContext context
    ) {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(
                        transactionManager
                );

        Integer updated =
                transactionTemplate.execute(status ->
                        turnRepository.markSucceeded(
                                context.turnId(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "requested-model",
                                "resolved-model",
                                "request-id",
                                clock.instant().plusSeconds(1)
                        )
                );

        return Objects.requireNonNull(
                updated,
                "Результат markSucceeded не должен быть null"
        );
    }

    private SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_USER"
                        )
                )
        );
    }

    private void alignChatTimestamps() {
        Instant fixtureTimestamp =
                clock.instant().minusSeconds(1);

        Timestamp timestamp =
                Timestamp.from(fixtureTimestamp);

        int updated = jdbcTemplate.update(
                """
                update public.chat_sessions
                   set created_at = ?,
                       updated_at = ?
                 where id = ?
                """,
                timestamp,
                timestamp,
                CHAT_ID
        );

        assertThat(updated)
                .as(
                        "Должен существовать тестовый чат для "
                                + "синхронизации timestamp: chatId=%s",
                        CHAT_ID
                )
                .isEqualTo(1);
    }

    private void assertOwnedChatVisible() {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                select exists (
                    select 1
                    from public.chat_sessions
                    where id = ?
                      and user_id = ?
                      and organization_id = ?
                )
                """,
                Boolean.class,
                CHAT_ID,
                USER_ID,
                ORGANIZATION_ID
        );

        assertThat(exists)
                .as(
                        "Тестовый чат должен принадлежать principal: "
                                + "chatId=%s, userId=%s, organizationId=%s",
                        CHAT_ID,
                        USER_ID,
                        ORGANIZATION_ID
                )
                .isTrue();
    }

    /**
     * Guards the V65 invariant inside the state-machine suite: the successful
     * ChatTurn must be backed by one ALLOWED decision bound to the exact
     * immutable catalog snapshot and organization policy seeded above.
     */
    private void assertAllowedRouteBoundToTestPolicy(
            ChatProcessingContext context
    ) {
        RouteGovernanceBinding binding =
                jdbcTemplate.queryForObject(
                        """
                        select
                            outcome,
                            policy_id,
                            policy_version,
                            selected_model_key,
                            selected_provider,
                            selected_provider_model_id
                        from public.model_route_decisions
                        where id = ?
                        """,
                        (resultSet, ignoredRowNumber) ->
                                new RouteGovernanceBinding(
                                        resultSet.getString(
                                                "outcome"
                                        ),
                                        resultSet.getObject(
                                                "policy_id",
                                                UUID.class
                                        ),
                                        resultSet.getObject(
                                                "policy_version",
                                                Integer.class
                                        ),
                                        resultSet.getString(
                                                "selected_model_key"
                                        ),
                                        resultSet.getString(
                                                "selected_provider"
                                        ),
                                        resultSet.getString(
                                                "selected_provider_model_id"
                                        )
                                ),
                        context.modelRouteDecisionId()
                );

        assertThat(binding)
                .isNotNull();

        assertThat(binding.outcome())
                .isEqualTo("ALLOWED");

        assertThat(binding.policyId())
                .isEqualTo(TEST_POLICY_ID);

        assertThat(binding.policyVersion())
                .isEqualTo(TEST_POLICY_VERSION);

        assertThat(binding.selectedModelKey())
                .isEqualTo(TEST_MODEL_KEY);

        assertThat(binding.selectedProvider())
                .isEqualTo("mock");

        assertThat(binding.selectedProviderModelId())
                .isEqualTo("mock-safeai");
    }

    private long countChatTurns() {
        return requireCount(
                jdbcTemplate.queryForObject(
                        "select count(*) from chat_turns",
                        Long.class
                ),
                "chat_turns"
        );
    }

    private long countChatMessages() {
        return requireCount(
                jdbcTemplate.queryForObject(
                        "select count(*) from chat_messages",
                        Long.class
                ),
                "chat_messages"
        );
    }

    private long countChatQuotaReservations() {
        return requireCount(
                jdbcTemplate.queryForObject(
                        "select count(*) from chat_quota_reservations",
                        Long.class
                ),
                "chat_quota_reservations"
        );
    }

    private static long requireCount(
            Long value,
            String table
    ) {
        return Objects.requireNonNull(
                value,
                "COUNT(*) вернул null для " + table
        );
    }

    private RouteIdentity loadRouteIdentity(
            ChatProcessingContext context
    ) {
        var turn = turnRepository
                .findById(context.turnId())
                .orElseThrow();

        UUID routeDecisionId =
                Objects.requireNonNull(
                        turn.getModelRouteDecisionId(),
                        "V45 turn должен содержать modelRouteDecisionId"
                );

        return new RouteIdentity(
                routeDecisionId,
                turn.getProvider(),
                turn.getRequestedModel()
        );
    }

    private RouteIdentity loadDecisionRouteIdentity(
            UUID routeDecisionId
    ) {
        RouteIdentity identity =
                jdbcTemplate.queryForObject(
                        """
                        select
                            id,
                            selected_provider,
                            selected_provider_model_id
                        from model_route_decisions
                        where id = ?
                        """,
                        (resultSet, ignoredRowNumber) ->
                                new RouteIdentity(
                                        resultSet.getObject(
                                                "id",
                                                UUID.class
                                        ),
                                        resultSet.getString(
                                                "selected_provider"
                                        ),
                                        resultSet.getString(
                                                "selected_provider_model_id"
                                        )
                                ),
                        routeDecisionId
                );

        return Objects.requireNonNull(
                identity,
                "Route decision должен существовать: "
                        + routeDecisionId
        );
    }

    private void assertRouteIdentityUnchanged(
            ChatProcessingContext context,
            RouteIdentity expected
    ) {
        RouteIdentity actual =
                loadRouteIdentity(context);

        assertThat(actual)
                .isEqualTo(expected);

        assertThat(
                loadDecisionRouteIdentity(
                        actual.modelRouteDecisionId()
                )
        )
                .isEqualTo(expected);
    }

    private String turnState(
            UUID turnId
    ) {
        return jdbcTemplate.queryForObject(
                """
                select state
                from chat_turns
                where id = ?
                """,
                String.class,
                turnId
        );
    }

    private String quotaState(
            UUID turnId
    ) {
        return jdbcTemplate.queryForObject(
                """
                select state
                from chat_quota_reservations
                where turn_id = ?
                """,
                String.class,
                turnId
        );
    }

    /**
     * Time-based recovery must be simulated by advancing the injected test
     * clock, not by writing an invalid PROCESSING row. V67 requires
     * lease_until > updated_at; it also protects immutable turn identity.
     * <p>
     * The persisted lease, provider-call marker, fencing token and identity
     * must remain unchanged until the production recovery code runs.
     */
    private void expire(UUID turnId) {
        Instant now = clock.instant();

        ExpiredTurnLeaseEvidence before = Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        """
                        select created_at, updated_at, provider_call_started_at,
                               state, processing_token, lease_until
                        from public.chat_turns
                        where id = ?
                        """,
                        (rs, row) -> new ExpiredTurnLeaseEvidence(
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant(),
                                rs.getTimestamp("provider_call_started_at") == null
                                        ? null
                                        : rs.getTimestamp("provider_call_started_at").toInstant(),
                                rs.getString("state"),
                                rs.getObject("processing_token", UUID.class),
                                rs.getTimestamp("lease_until").toInstant()
                        ),
                        turnId
                ),
                "PROCESSING ChatTurn must exist before simulating expiration"
        );

        assertThat(before.state()).isEqualTo("PROCESSING");
        assertThat(before.processingToken()).isNotNull();
        assertThat(before.leaseUntil()).isAfter(before.updatedAt());
        assertThat(before.leaseUntil()).isAfter(now);

        adjustableClock().advanceTo(before.leaseUntil().plusSeconds(1));

        assertThat(clock.instant()).isAfter(before.leaseUntil());

        ExpiredTurnLeaseEvidence after = Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                        """
                        select created_at, updated_at, provider_call_started_at,
                               state, processing_token, lease_until
                        from public.chat_turns
                        where id = ?
                        """,
                        (rs, row) -> new ExpiredTurnLeaseEvidence(
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant(),
                                rs.getTimestamp("provider_call_started_at") == null
                                        ? null
                                        : rs.getTimestamp("provider_call_started_at").toInstant(),
                                rs.getString("state"),
                                rs.getObject("processing_token", UUID.class),
                                rs.getTimestamp("lease_until").toInstant()
                        ),
                        turnId
                ),
                "ChatTurn must remain present after advancing the test clock"
        );

        assertThat(after).isEqualTo(before);
    }

    private ChatIntegrationClockConfiguration.AdjustableClock adjustableClock() {
        assertThat(clock)
                .as("Chat state-machine recovery tests require an adjustable test Clock")
                .isInstanceOf(ChatIntegrationClockConfiguration.AdjustableClock.class);
        return (ChatIntegrationClockConfiguration.AdjustableClock) clock;
    }

    private record ExpiredTurnLeaseEvidence(
            Instant createdAt,
            Instant updatedAt,
            Instant providerCallStartedAt,
            String state,
            UUID processingToken,
            Instant leaseUntil
    ) { }

    private static AiProviderException
    unambiguousProviderFailure() {
        return new AiProviderException(
                "openai",
                "requested-model",
                400,
                "provider-request-id",
                AiProviderErrorType.INVALID_REQUEST,
                false,
                false,
                null,
                "provider failure",
                null
        );
    }

    private static AiChatResponse response(
            AiResponseStatus status,
            String finishReason
    ) {
        return new AiChatResponse(
                "Provider response",
                "requested-model",
                "resolved-model",
                "provider-message-id",
                "provider-request-id",
                status,
                finishReason,
                10,
                20,
                UsageStatus.AVAILABLE,
                BigDecimal.ZERO,
                PricingStatus.FREE,
                "USD",
                "mock-2026-01",
                NOW
        );
    }

    private record RouteIdentity(
            UUID modelRouteDecisionId,
            String provider,
            String requestedModel
    ) {
    }

    private record RouteGovernanceBinding(
            String outcome,
            UUID policyId,
            Integer policyVersion,
            String selectedModelKey,
            String selectedProvider,
            String selectedProviderModelId
    ) {
    }

}

