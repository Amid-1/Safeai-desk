package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.pricing.PricingResult;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiRetryProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiExecutionServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-09-13T12:00:00Z");

    private static final UUID CHAT_TURN_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ROUTE_DECISION_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID USER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID CHAT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000005");
    private static final UUID OPERATION_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000006");

    @Mock
    private AiProvider provider;

    @Mock
    private AiExecutionPlanService planService;

    @Mock
    private AiExecutionAttemptRecorder recorder;

    private AiExecutionAttemptScope scope;
    private AiExecutionService service;
    private ModelExecutionPlanEntity plan;
    private ProviderExecutionTarget target;
    private AiExecutionRequest request;

    @BeforeEach
    void setUp() {
        scope = new AiExecutionAttemptScope();
        service = new AiExecutionService(
                provider,
                new AiProviderProperties("openai"),
                planService,
                recorder,
                scope
        );
        target = ProviderExecutionTarget.staticTarget(
                "openai",
                "gpt-5"
        );
        request = new AiExecutionRequest(
                CHAT_TURN_ID,
                ROUTE_DECISION_ID,
                new AiChatRequest(
                        USER_ID,
                        ORGANIZATION_ID,
                        CHAT_ID,
                        OPERATION_ID,
                        null,
                        null,
                        "question",
                        List.of()
                ),
                target
        );
        plan = ModelExecutionPlanEntity.create(
                OPERATION_ID,
                CHAT_TURN_ID,
                ORGANIZATION_ID,
                ROUTE_DECISION_ID,
                "gpt-5",
                NOW
        );

        lenient().when(provider.executionTarget())
                .thenReturn(target);
    }

    @Test
    void recordsStartBeforeSuccessfulTerminalEvidence() {
        AiProviderAttemptContext attempt =
                new AiProviderAttemptContext(
                        OPERATION_ID,
                        UUID.randomUUID(),
                        1,
                        1
                );
        AiChatResponse response = response(
                "gpt-5",
                "gpt-5"
        );

        when(provider.recordsPhysicalAttempts())
                .thenReturn(true);
        when(planService.createOrRead(request))
                .thenReturn(plan);
        when(provider.sendMessage(request.aiRequest()))
                .thenAnswer(invocation -> {
                    scope.started(attempt);
                    scope.succeeded(attempt, response);
                    return response;
                });

        AiExecutionResult result = service.execute(request);

        assertThat(result.executionPlanId())
                .isEqualTo(plan.getId());
        assertThat(result.response())
                .isSameAs(response);

        InOrder order = inOrder(recorder);
        order.verify(recorder).started(
                plan.getId(),
                target,
                attempt
        );
        order.verify(recorder).succeeded(
                attempt.attemptId(),
                response
        );
    }

    @Test
    void resolvedModelMismatchPreservesPhysicalSuccessAndFailsClosed() {
        AiProviderAttemptContext attempt =
                new AiProviderAttemptContext(
                        OPERATION_ID,
                        UUID.randomUUID(),
                        1,
                        1
                );
        AiChatResponse response = response(
                "gpt-5",
                "gpt-5-2027-preview"
        );

        when(provider.recordsPhysicalAttempts())
                .thenReturn(true);
        when(planService.createOrRead(request))
                .thenReturn(plan);
        when(provider.sendMessage(request.aiRequest()))
                .thenAnswer(invocation -> {
                    scope.started(attempt);
                    scope.succeeded(attempt, response);
                    return response;
                });

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(ResolvedModelMismatchException.class);

        verify(recorder).started(
                plan.getId(),
                target,
                attempt
        );
        verify(recorder).succeeded(
                attempt.attemptId(),
                response
        );
        verify(recorder, never()).ambiguous(
                any(),
                any()
        );
    }

    @Test
    void rejectsAdapterWithoutAttemptEvidenceBeforeCreatingPlanOrCallingIt() {
        when(provider.recordsPhysicalAttempts())
                .thenReturn(false);

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable attempt evidence");

        verify(planService, never()).createOrRead(any());
        verify(provider, never()).sendMessage(any());
    }

    @Test
    void rejectsDifferentPhysicalTargetBeforeCreatingPlanOrCallingProvider() {
        ProviderExecutionTarget differentTarget =
                ProviderExecutionTarget.staticTarget(
                        "openai",
                        "gpt-5-premium"
                );
        AiExecutionRequest mismatched =
                new AiExecutionRequest(
                        CHAT_TURN_ID,
                        ROUTE_DECISION_ID,
                        request.aiRequest(),
                        differentTarget
                );

        when(provider.recordsPhysicalAttempts())
                .thenReturn(true);

        assertThatThrownBy(() -> service.execute(mismatched))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active provider configuration");

        verify(planService, never()).createOrRead(any());
        verify(provider, never()).sendMessage(any());
    }

    @Test
    void rejectsAttemptFromAnotherProviderOperationBeforePersistence() {
        AiProviderAttemptContext foreignAttempt =
                new AiProviderAttemptContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        1
                );

        when(provider.recordsPhysicalAttempts())
                .thenReturn(true);
        when(planService.createOrRead(request))
                .thenReturn(plan);
        when(provider.sendMessage(request.aiRequest()))
                .thenAnswer(invocation -> {
                    scope.started(foreignAttempt);
                    return response("gpt-5", "gpt-5");
                });

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operationId");

        verify(recorder, never()).started(
                any(),
                any(),
                any()
        );
    }

    @Test
    void recordsEveryPhysicalRetryAsItsOwnAttempt() {
        AiProviderRetryExecutor retryExecutor =
                new AiProviderRetryExecutor(
                        new AiRetryProperties(
                                true,
                                2,
                                Duration.ofMillis(10),
                                Duration.ofMillis(10),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(2)
                        ),
                        scope
                );
        List<AiProviderAttemptContext> attempts =
                new ArrayList<>();
        AiChatResponse response = response(
                "gpt-5",
                "gpt-5"
        );
        AiProviderRateLimitedException firstFailure =
                new AiProviderRateLimitedException(
                        "openai",
                        "gpt-5",
                        429,
                        "request-1",
                        Duration.ZERO,
                        true,
                        "rate limited",
                        null
                );

        when(provider.recordsPhysicalAttempts())
                .thenReturn(true);
        when(planService.createOrRead(request))
                .thenReturn(plan);
        when(provider.sendMessage(request.aiRequest()))
                .thenAnswer(invocation -> retryExecutor.execute(
                        "openai",
                        "gpt-5",
                        OPERATION_ID,
                        Duration.ZERO,
                        attempt -> {
                            attempts.add(attempt);

                            if (attempt.attemptNumber() == 1) {
                                throw firstFailure;
                            }

                            return response;
                        }
                ));

        AiExecutionResult result = service.execute(request);

        assertThat(result.response()).isSameAs(response);
        assertThat(attempts).hasSize(2);

        InOrder order = inOrder(recorder);
        order.verify(recorder).started(
                plan.getId(),
                target,
                attempts.get(0)
        );
        order.verify(recorder).failed(
                attempts.get(0).attemptId(),
                firstFailure
        );
        order.verify(recorder).started(
                plan.getId(),
                target,
                attempts.get(1)
        );
        order.verify(recorder).succeeded(
                attempts.get(1).attemptId(),
                response
        );
    }

    private static AiChatResponse response(
            String requestedModel,
            String resolvedModel
    ) {
        return AiChatResponse.fromProvider(
                "answer",
                requestedModel,
                resolvedModel,
                "message-id",
                "request-id",
                AiResponseStatus.COMPLETED,
                "stop",
                new AiTokenUsage(
                        10,
                        4,
                        2,
                        5,
                        true,
                        true
                ),
                PricingResult.unpriced(NOW)
        );
    }
}
