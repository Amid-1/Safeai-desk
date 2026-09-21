package ru.safeai.gateway.chat.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.service.ChatContentNormalizer;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelRouteResult;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.service.ModelRouteReservedRequestService;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
class ChatDatabaseStateConstraintIntegrationTest
        extends AbstractChatPostgresIntegrationTest {

    private static final Instant EXECUTION_AT = ModelTestFixtures.NOW;
    private static final String EXECUTION_PROVIDER = "openai";
    private static final String EXECUTION_MODEL = "gpt-test";
    private static final String QUESTION = "Question";

    @Autowired
    private ChatContentNormalizer contentNormalizer;

    @Autowired
    private ModelRouteReservedRequestService reservedRequestService;

    @Test
    void succeededTurnWithoutRequestedAndResolvedModelIsRejected() {
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Question",
                NOW.minusSeconds(2)
        );
        UUID assistantMessageId = insertCompletedAssistant(
                CHAT_ID,
                ORGANIZATION_ID,
                userMessageId,
                "Answer",
                "COMPLETED",
                NOW.minusSeconds(1)
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.chat_turns (
                    id, session_id, organization_id, user_id,
                    client_request_id, request_content_hash,
                    provider_operation_id, user_message_id,
                    assistant_message_id, state, processing_token,
                    lease_until, provider_call_started_at, provider,
                    requested_model, resolved_model, outcome_ambiguous,
                    created_at, updated_at, completed_at, version
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', null,
                    null, ?, 'mock', null, null, false, ?, ?, ?, 0
                )
                """,
                UUID.randomUUID(),
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                clientRequestId,
                "1".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                assistantMessageId,
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ambiguousTurnWithoutProviderCallMarkerIsRejected() {
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Question",
                NOW.minusSeconds(2)
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.chat_turns (
                    id, session_id, organization_id, user_id,
                    client_request_id, request_content_hash,
                    provider_operation_id, user_message_id,
                    state, processing_token, lease_until,
                    provider_call_started_at, provider,
                    provider_error_type, failure_code,
                    outcome_ambiguous, created_at, updated_at,
                    completed_at, version
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, 'AMBIGUOUS', null, null,
                    null, 'mock', 'TIMEOUT', 'AI_PROVIDER_OUTCOME_AMBIGUOUS',
                    true, ?, ?, ?, 0
                )
                """,
                UUID.randomUUID(),
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                clientRequestId,
                "2".repeat(64),
                UUID.randomUUID(),
                userMessageId,
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void settledPricedQuotaWithoutActualCostIsRejected() {
        UUID turnId = processingTurnWithUserMessage();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.chat_quota_reservations (
                    turn_id, organization_id, user_id, period_start,
                    state, reserved_input_tokens, reserved_output_tokens,
                    reserved_cost_usd, actual_input_tokens,
                    actual_output_tokens, actual_cost_usd,
                    usage_status, pricing_status,
                    created_at, updated_at, settled_at
                ) values (
                    ?, ?, ?, ?, 'SETTLED', 16000, 2048,
                    1.000000000000, 10, 20, null,
                    'AVAILABLE', 'PRICED', ?, ?, ?
                )
                """,
                turnId,
                ORGANIZATION_ID,
                USER_ID,
                Date.valueOf(LocalDate.of(2026, 6, 1)),
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unpricedQuotaCannotMasqueradeAsKnownZeroCost() {
        UUID turnId = processingTurnWithUserMessage();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into public.chat_quota_reservations (
                    turn_id, organization_id, user_id, period_start,
                    state, reserved_input_tokens, reserved_output_tokens,
                    reserved_cost_usd, actual_input_tokens,
                    actual_output_tokens, actual_cost_usd,
                    usage_status, pricing_status,
                    created_at, updated_at, settled_at
                ) values (
                    ?, ?, ?, ?, 'UNPRICED', 16000, 2048,
                    1.000000000000, 10, 20, 0,
                    'AVAILABLE', 'UNPRICED', ?, ?, ?
                )
                """,
                turnId,
                ORGANIZATION_ID,
                USER_ID,
                Date.valueOf(LocalDate.of(2026, 6, 1)),
                Timestamp.from(NOW.minusSeconds(2)),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void startedExecutionAttemptCannotBeDeleted() {
        ExecutionIds execution = insertStartedExecutionAttempt();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from public.model_execution_attempts where id = ?",
                execution.attemptId()
        )).isInstanceOf(DataAccessException.class);

        Integer remaining = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.model_execution_attempts
                where id = ?
                """,
                Integer.class,
                execution.attemptId()
        );

        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void terminalTransitionCannotChangeAttemptIdentity() {
        ExecutionIds execution = insertStartedExecutionAttempt();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome = 'AMBIGUOUS',
                    outcome_certainty = 'AMBIGUOUS',
                    finished_at = ?,
                    failure_type = 'UNCLASSIFIED_FAILURE',
                    deployment_ref = 'tampered:deployment'
                where id = ?
                """,
                Timestamp.from(EXECUTION_AT.plusSeconds(2)),
                execution.attemptId()
        )).isInstanceOf(DataAccessException.class);

        Map<String, Object> persisted = jdbcTemplate.queryForMap(
                """
                select outcome, deployment_ref
                from public.model_execution_attempts
                where id = ?
                """,
                execution.attemptId()
        );

        assertThat(persisted.get("outcome"))
                .isEqualTo("STARTED");
        assertThat(persisted.get("deployment_ref"))
                .isEqualTo("static:openai:gpt-test");
    }

    @Test
    void successfulAttemptPersistsCompleteCacheAndPricingEvidence() {
        ExecutionIds execution = insertStartedExecutionAttempt();

        int updated = jdbcTemplate.update(
                """
                update public.model_execution_attempts
                set outcome = 'SUCCEEDED',
                    outcome_certainty = 'KNOWN_EXECUTED',
                    resolved_physical_model = 'gpt-test',
                    provider_request_id = 'provider-request-1',
                    provider_message_id = 'provider-message-1',
                    usage_status = 'AVAILABLE',
                    input_tokens = 100,
                    cached_input_tokens = 80,
                    cache_write_input_tokens = 10,
                    output_tokens = 20,
                    specialized_dimensions_present = true,
                    specialized_dimensions_valid = true,
                    pricing_status = 'FREE',
                    cost_usd = 0,
                    currency = 'USD',
                    price_book_version = 'mock-2026-01',
                    finished_at = ?
                where id = ?
                """,
                Timestamp.from(EXECUTION_AT.plusSeconds(2)),
                execution.attemptId()
        );

        assertThat(updated).isEqualTo(1);

        Map<String, Object> evidence = jdbcTemplate.queryForMap(
                """
                select outcome, input_tokens, cached_input_tokens,
                       cache_write_input_tokens, output_tokens,
                       pricing_status, cost_usd, price_book_version
                from public.model_execution_attempts
                where id = ?
                """,
                execution.attemptId()
        );

        assertThat(evidence.get("outcome"))
                .isEqualTo("SUCCEEDED");
        assertThat(evidence.get("input_tokens"))
                .isEqualTo(100);
        assertThat(evidence.get("cached_input_tokens"))
                .isEqualTo(80);
        assertThat(evidence.get("cache_write_input_tokens"))
                .isEqualTo(10);
        assertThat(evidence.get("output_tokens"))
                .isEqualTo(20);
        assertThat(evidence.get("pricing_status"))
                .isEqualTo("FREE");
        assertThat(evidence.get("price_book_version"))
                .isEqualTo("mock-2026-01");
    }

    /**
     * V53/V54 permit physical execution only for an exact catalog-backed v3
     * ALLOWED route, committed ChatTurn and immutable pre-RAG request seal.
     * Prepare all four records atomically; do not weaken DB constraints or
     * create a legacy catalog-less plan for a physical-attempt test.
     */
    private ExecutionIds insertStartedExecutionAttempt() {
        // Catalog is append-only and survives other methods in this test class.
        // A new logical model key per fixture prevents (model_key, version=1)
        // collisions while retaining the exact same physical provider/model.
        String catalogModelKey =
                "test:execution-integrity:" + UUID.randomUUID();
        ModelCatalogEntry template = ModelTestFixtures.freeEntry();
        ModelCatalogEntry entry = new ModelCatalogEntry(
                UUID.randomUUID(),
                catalogModelKey,
                1,
                EXECUTION_PROVIDER,
                EXECUTION_MODEL,
                "Execution integrity test model",
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
                EXECUTION_AT.minusSeconds(120),
                template.source(),
                USER_ID,
                EXECUTION_AT.minusSeconds(180)
        );

        ModelCatalogRepository catalog =
                new ModelCatalogRepository(jdbcTemplate);

        RuntimeModelStatusService runtime =
                mock(RuntimeModelStatusService.class);
        when(runtime.current()).thenReturn(ModelTestFixtures.freeRuntime());

        ModelRoutingService routing = new ModelRoutingService(
                catalog,
                new OrganizationModelPolicyRepository(jdbcTemplate),
                new ModelRouteDecisionRepository(jdbcTemplate),
                runtime,
                mock(AuditEventService.class),
                ModelTestFixtures.CLOCK
        );

        UUID turnId = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();
        UUID providerOperationId = UUID.randomUUID();
        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                QUESTION,
                EXECUTION_AT.minusSeconds(20)
        );
        String requestHash = contentNormalizer.requestHash(
                QUESTION,
                null,
                KnowledgeMode.GENERAL
        );
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );

        // The catalog, route->planned-turn FK, turn, seal, plan and attempt
        // must commit atomically. No orphaned fixture rows on setup failure.
        return new TransactionTemplate(transactionManager).execute(tx -> {
            catalog.insert(entry);
            ModelRouteResult route = routing.decide(
                    new ModelRouteRequest(
                            ORGANIZATION_ID,
                            USER_ID,
                            CHAT_ID,
                            turnId,
                            clientRequestId,
                            requestHash,
                            catalogModelKey,
                            QUESTION,
                            List.of(),
                            Set.of(),
                            0L
                    ),
                    principal
            );

            assertThat(route.catalogEntryId()).isEqualTo(entry.id());
            assertThat(route.modelKey()).isEqualTo(catalogModelKey);
            assertThat(route.catalogVersion()).isEqualTo(1);
            assertThat(route.provider()).isEqualTo(EXECUTION_PROVIDER);
            assertThat(route.providerModelId()).isEqualTo(EXECUTION_MODEL);

            jdbcTemplate.update(
                    """
                    insert into public.chat_turns (
                        id, session_id, organization_id, user_id,
                        client_request_id, request_content_hash,
                        provider_operation_id, user_message_id,
                        state, processing_token, lease_until,
                        provider_call_started_at, provider, requested_model,
                        model_route_decision_id, outcome_ambiguous,
                        created_at, updated_at, version
                    ) values (
                        ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?,
                        ?, ?, ?, ?, false, ?, ?, 0
                    )
                    """,
                    turnId,
                    CHAT_ID,
                    ORGANIZATION_ID,
                    USER_ID,
                    clientRequestId,
                    requestHash,
                    providerOperationId,
                    userMessageId,
                    UUID.randomUUID(),
                    Timestamp.from(EXECUTION_AT.plusSeconds(60)),
                    Timestamp.from(EXECUTION_AT),
                    EXECUTION_PROVIDER,
                    EXECUTION_MODEL,
                    route.decisionId(),
                    Timestamp.from(EXECUTION_AT.minusSeconds(10)),
                    Timestamp.from(EXECUTION_AT.minusSeconds(10))
            );

            AiChatRequest baseRequest = new AiChatRequest(
                    USER_ID,
                    ORGANIZATION_ID,
                    CHAT_ID,
                    providerOperationId,
                    null,
                    null,
                    QUESTION,
                    List.of(),
                    route.estimatedInputTokens(),
                    Math.toIntExact(route.estimatedOutputTokens())
            );
            reservedRequestService.seal(
                    route.decisionId(),
                    turnId,
                    clientRequestId,
                    baseRequest,
                    null,
                    KnowledgeMode.GENERAL
            );

            UUID planId = UUID.randomUUID();
            UUID attemptId = UUID.randomUUID();

            jdbcTemplate.update(
                    """
                    insert into public.model_execution_plans (
                        id, provider_operation_id, chat_turn_id,
                        organization_id, model_route_decision_id,
                        requested_model, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    planId,
                    providerOperationId,
                    turnId,
                    ORGANIZATION_ID,
                    route.decisionId(),
                    EXECUTION_MODEL,
                    Timestamp.from(EXECUTION_AT)
            );

            jdbcTemplate.update(
                    """
                    insert into public.model_execution_attempts (
                        id, execution_plan_id, attempt_number,
                        provider_attempt_id, provider_type,
                        provider_configuration_ref,
                        provider_configuration_version,
                        deployment_ref, deployment_version,
                        requested_physical_model, started_at,
                        outcome, outcome_certainty,
                        retry_safety, fallback_safety, created_at
                    ) values (
                        ?, ?, 1, ?, ?, ?, 'static-v1',
                        ?, 'static-v1', ?, ?, 'STARTED', 'AMBIGUOUS',
                        'SAME_TARGET_RETRY_FORBIDDEN',
                        'FALLBACK_FORBIDDEN', ?
                    )
                    """,
                    attemptId,
                    planId,
                    UUID.randomUUID(),
                    EXECUTION_PROVIDER,
                    "static:" + EXECUTION_PROVIDER,
                    "static:" + EXECUTION_PROVIDER + ":" + EXECUTION_MODEL,
                    EXECUTION_MODEL,
                    Timestamp.from(EXECUTION_AT.plusSeconds(1)),
                    Timestamp.from(EXECUTION_AT.plusSeconds(1))
            );

            return new ExecutionIds(planId, attemptId);
        });
    }

    private UUID processingTurnWithUserMessage() {
        UUID clientRequestId = UUID.randomUUID();
        UUID userMessageId = insertUserMessage(
                CHAT_ID,
                ORGANIZATION_ID,
                clientRequestId,
                "Question",
                NOW.minusSeconds(10)
        );
        return insertProcessingTurn(
                CHAT_ID,
                ORGANIZATION_ID,
                USER_ID,
                clientRequestId,
                userMessageId,
                UUID.randomUUID(),
                NOW.plusSeconds(60),
                null
        );
    }

    private record ExecutionIds(
            UUID planId,
            UUID attemptId
    ) {
    }
}
