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
import ru.safeai.gateway.model.service.ModelRouteExecutionBindingService;
import ru.safeai.gateway.model.service.ModelRouteExecutionIdentity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V53/V54: execution is impossible without DB-backed request/route binding. */
@ExtendWith(MockitoExtension.class)
class AiExecutionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
    private static final UUID TURN = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DECISION = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID USER = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID ORG = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID CHAT = UUID.fromString("10000000-0000-4000-8000-000000000005");
    private static final UUID OPERATION = UUID.fromString("10000000-0000-4000-8000-000000000006");

    @Mock AiProvider provider;
    @Mock AiExecutionPlanService plans;
    @Mock AiExecutionAttemptRecorder recorder;
    @Mock ModelRouteExecutionBindingService binding;
    @Mock ModelRouteExecutionIdentity identity;

    private AiExecutionAttemptScope scope;
    private AiExecutionService service;
    private AiExecutionRequest request;
    private ModelExecutionPlanEntity plan;
    private ProviderExecutionTarget target;

    @BeforeEach
    void setUp() {
        scope = new AiExecutionAttemptScope();
        service = new AiExecutionService(
                provider, new AiProviderProperties("openai"), plans, recorder, scope, binding);
        target = ProviderExecutionTarget.staticTarget("openai", "gpt-5");
        AiChatRequest base = new AiChatRequest(USER, ORG, CHAT, OPERATION,
                null, null, "question", List.of(), 4_096L, 512);
        lenient().when(identity.decisionId()).thenReturn(DECISION);
        lenient().when(identity.chatTurnId()).thenReturn(TURN);
        request = new AiExecutionRequest(TURN, DECISION, base, target, identity, base);
        plan = ModelExecutionPlanEntity.create(OPERATION, TURN, ORG, DECISION, "gpt-5", NOW);
        lenient().when(provider.executionTarget()).thenReturn(target);
    }

    @Test
    void guardPrecedesPlanAndEveryPhysicalSideEffect() {
        AiProviderAttemptContext attempt = firstPhysicalAttempt();
        AiChatResponse response = response("gpt-5");
        when(provider.recordsPhysicalAttempts()).thenReturn(true);
        when(plans.createOrRead(request)).thenReturn(plan);
        when(provider.sendMessage(request.aiRequest())).thenAnswer(inv -> {
            scope.started(attempt);
            scope.succeeded(attempt, response);
            return response;
        });

        AiExecutionResult result = service.execute(request);
        assertThat(result.executionPlanId()).isEqualTo(plan.getId());
        assertThat(result.response()).isSameAs(response);
        InOrder order = inOrder(binding, plans, provider, recorder);
        order.verify(binding).requireExecutable(identity, TURN, DECISION,
                request.reservedAiRequest(), request.aiRequest(), "openai", "gpt-5");
        order.verify(plans).createOrRead(request);
        order.verify(provider).sendMessage(request.aiRequest());
        order.verify(recorder).started(plan.getId(), target, attempt);
        order.verify(recorder).succeeded(attempt.attemptId(), response);
    }

    @Test
    void badReservedIdentityFailsBeforePlanAndAdapter() {
        doThrow(new IllegalStateException("Pre-RAG request differs from sealed reservation"))
                .when(binding).requireExecutable(eq(identity), eq(TURN), eq(DECISION),
                        any(), any(), eq("openai"), eq("gpt-5"));
        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sealed reservation");
        verifyNoInteractions(plans, recorder);
        verifyNoInteractions(provider);
    }

    @Test
    void adapterWithoutEvidenceFailsBeforePlanAndProviderIo() {
        when(provider.recordsPhysicalAttempts()).thenReturn(false);
        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable attempt evidence");
        verifyNoInteractions(plans, recorder);
        verify(provider, never()).sendMessage(any());
    }

    @Test
    void mismatchedPhysicalTargetFailsBeforePlanAndProviderIo() {
        when(provider.recordsPhysicalAttempts()).thenReturn(true);
        ProviderExecutionTarget different = ProviderExecutionTarget.staticTarget("openai", "gpt-6");
        AiExecutionRequest bad = new AiExecutionRequest(TURN, DECISION,
                request.aiRequest(), different, identity, request.reservedAiRequest());
        assertThatThrownBy(() -> service.execute(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active provider configuration");
        verifyNoInteractions(plans, recorder);
        verify(provider, never()).sendMessage(any());
    }

    @Test
    void foreignOperationAttemptCannotCreatePhysicalEvidence() {
        when(provider.recordsPhysicalAttempts()).thenReturn(true);
        when(plans.createOrRead(request)).thenReturn(plan);
        AiProviderAttemptContext foreign = new AiProviderAttemptContext(
                UUID.randomUUID(), UUID.randomUUID(), 1, 1);
        when(provider.sendMessage(request.aiRequest())).thenAnswer(inv -> {
            scope.started(foreign);
            return response("gpt-5");
        });
        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("operationId");
        verifyNoInteractions(recorder);
    }

    @Test
    void resolvedModelMismatchPreservesSucceededPhysicalAttemptWithoutFalseFailure() {
        AiProviderAttemptContext attempt = firstPhysicalAttempt();
        AiChatResponse response = response("gpt-5-unapproved");
        when(provider.recordsPhysicalAttempts()).thenReturn(true);
        when(plans.createOrRead(request)).thenReturn(plan);
        when(provider.sendMessage(request.aiRequest())).thenAnswer(inv -> {
            scope.started(attempt);
            scope.succeeded(attempt, response);
            return response;
        });
        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(ResolvedModelMismatchException.class);
        InOrder order = inOrder(recorder);
        order.verify(recorder).started(plan.getId(), target, attempt);
        order.verify(recorder).succeeded(attempt.attemptId(), response);
        verify(recorder, never()).failed(any(), any());
        verify(recorder, never()).ambiguous(any(), any());
    }

    @Test
    void terminalEvidenceWriteErrorRequiresReconciliationAndCannotRetry() {
        AiProviderAttemptContext attempt = firstPhysicalAttempt();
        AiChatResponse response = response("gpt-5");
        when(provider.recordsPhysicalAttempts()).thenReturn(true);
        when(plans.createOrRead(request)).thenReturn(plan);
        doThrow(new IllegalStateException("DB acknowledgement lost"))
                .when(recorder).succeeded(attempt.attemptId(), response);
        when(provider.sendMessage(request.aiRequest())).thenAnswer(inv -> {
            scope.started(attempt);
            scope.succeeded(attempt, response);
            return response;
        });
        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(PhysicalAttemptReconciliationRequiredException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(error -> {
                    PhysicalAttemptReconciliationRequiredException reconciliation =
                            (PhysicalAttemptReconciliationRequiredException) error;

                    assertThat(reconciliation.providerAttemptId())
                            .isEqualTo(attempt.attemptId());
                });
        verify(provider, times(1)).sendMessage(any());
        verify(recorder, never()).failed(any(), any());
        verify(recorder, never()).ambiguous(any(), any());
    }

    @Test
    void softPolicyCanRetryWithinSameExecuteCallAndRecordsBothAttempts() {
        when(provider.recordsPhysicalAttempts()).thenReturn(true);
        when(plans.createOrRead(request)).thenReturn(plan);
        AiProviderRetryExecutor retry = retry(2);
        List<AiProviderAttemptContext> physical = new ArrayList<>();
        AiChatResponse response = response("gpt-5");
        AiProviderRateLimitedException rejection = rateLimited();
        when(provider.sendMessage(request.aiRequest())).thenAnswer(inv -> retry.execute(
                "openai", "gpt-5", OPERATION, Duration.ZERO, attempt -> {
                    physical.add(attempt);
                    if (attempt.attemptNumber() == 1) throw rejection;
                    return response;
                }));

        assertThat(service.execute(request).response()).isSameAs(response);
        assertThat(physical).hasSize(2);
        InOrder order = inOrder(recorder);
        order.verify(recorder).started(plan.getId(), target, physical.get(0));
        order.verify(recorder).failed(physical.get(0).attemptId(), rejection);
        order.verify(recorder).started(plan.getId(), target, physical.get(1));
        order.verify(recorder).succeeded(physical.get(1).attemptId(), response);
        verify(plans, times(1)).createOrRead(request);
    }

    @Test
    void hardPolicyLimitsEntireLogicalExecutionToOnePhysicalAttempt() {
        when(provider.recordsPhysicalAttempts()).thenReturn(true);
        when(binding.requiresSinglePhysicalAttempt(DECISION)).thenReturn(true);
        when(plans.createOrRead(request)).thenReturn(plan);
        AiProviderRetryExecutor retry = retry(3);
        AtomicInteger attempts = new AtomicInteger();
        when(provider.sendMessage(request.aiRequest())).thenAnswer(inv -> retry.execute(
                "openai", "gpt-5", OPERATION, Duration.ZERO, attempt -> {
                    attempts.incrementAndGet();
                    throw rateLimited();
                }));

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOf(AiProviderRateLimitedException.class);
        assertThat(attempts).hasValue(1);
        verify(recorder, times(1)).started(eq(plan.getId()), eq(target), any());
        verify(recorder, times(1)).failed(any(), any());
        verify(recorder, never()).succeeded(any(), any());
    }

    private AiProviderRetryExecutor retry(int max) {
        return new AiProviderRetryExecutor(new AiRetryProperties(true, max,
                Duration.ofMillis(10), Duration.ofMillis(10),
                Duration.ofSeconds(1), Duration.ofSeconds(2)), scope);
    }

    private static AiProviderRateLimitedException rateLimited() {
        return new AiProviderRateLimitedException("openai", "gpt-5", 429,
                "request-1", Duration.ZERO, true, "rate limited", null);
    }

    private static AiProviderAttemptContext firstPhysicalAttempt() {
        return new AiProviderAttemptContext(OPERATION, UUID.randomUUID(), 1, 1);
    }

    private static AiChatResponse response(String actual) {
        return AiChatResponse.fromProvider("answer", "gpt-5", actual,
                "message-id", "request-id", AiResponseStatus.COMPLETED, "stop",
                new AiTokenUsage(10, 4, 2, 5, true, true), PricingResult.unpriced(NOW));
    }
}
