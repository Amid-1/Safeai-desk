package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AiExecutionAttemptScopeTest {
    private static final UUID OPERATION = UUID.randomUUID();

    @Test
    void successfulEvidenceMustBeCommittedBeforeReturningResponse() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);
        AiProviderAttemptContext attempt = attempt(1);
        AiChatResponse response = mock(AiChatResponse.class);

        assertThat(scope.execute(OPERATION, observer, () -> {
            scope.started(attempt);
            assertThat(scope.hasActiveAttempt(attempt)).isTrue();
            scope.beforeNetworkDispatch(attempt);
            scope.succeeded(attempt, response);
            assertThat(scope.hasActiveAttempt(attempt)).isFalse();
            return response;
        })).isSameAs(response);

        var order = inOrder(observer);
        order.verify(observer).started(attempt);
        order.verify(observer).beforeNetworkDispatch(attempt);
        order.verify(observer).succeeded(attempt, response);
        verify(observer, never()).failed(any(), any());
        verify(observer, never()).ambiguous(any(), any());
        assertThat(scope.active()).isFalse();
    }

    @Test
    void lostDbAckNeverCreatesContradictoryTerminalEventOrSecondCall() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);
        AiProviderAttemptContext attempt = attempt(1);
        AiChatResponse response = mock(AiChatResponse.class);
        doThrow(new IllegalStateException("commit acknowledgement lost"))
                .when(observer).succeeded(attempt, response);
        AtomicInteger physicalCalls = new AtomicInteger();

        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.started(attempt);
            scope.beforeNetworkDispatch(attempt);
            physicalCalls.incrementAndGet();
            scope.succeeded(attempt, response);
            return response;
        })).isInstanceOf(PhysicalAttemptReconciliationRequiredException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(physicalCalls).hasValue(1);
        assertThat(scope.active()).isFalse();
        verify(observer, never()).failed(any(), any());
        verify(observer, never()).ambiguous(any(), any());
    }

    @Test
    void modelMismatchAfterCommittedSuccessIsNotReclassifiedAsAmbiguousPhysicalAttempt() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);
        AiProviderAttemptContext attempt = attempt(1);
        AiChatResponse response = mock(AiChatResponse.class);
        doThrow(new ResolvedModelMismatchException("openai", "gpt-5", "gpt-6", "req"))
                .when(observer).succeeded(attempt, response);

        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.started(attempt);
            scope.beforeNetworkDispatch(attempt);
            scope.succeeded(attempt, response);
            return response;
        })).isInstanceOf(ResolvedModelMismatchException.class);

        verify(observer, never()).failed(any(), any());
        verify(observer, never()).ambiguous(any(), any());
        assertThat(scope.active()).isFalse();
    }

    @Test
    void cannotStartSecondWhileFirstIsStartedAndCannotRepeatSequenceNumber() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);
        AiProviderAttemptContext first = attempt(1);
        AiProviderAttemptContext second = attempt(2);
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.started(first);
            assertThatThrownBy(() -> scope.started(second))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not terminal");
            scope.beforeNetworkDispatch(first);
            scope.failed(first, rateLimited());
            assertThatThrownBy(() -> scope.started(first))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sequentially");
            return mock(AiChatResponse.class);
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching successful physical evidence");

        verify(observer).started(first);
        verify(observer, never()).started(second);
        verify(observer).failed(eq(first), any(AiProviderException.class));
        verify(observer, never()).succeeded(any(), any());
        assertThat(scope.active()).isFalse();
    }

    @Test
    void hardCapIsVisibleToRetryExecutorAndScopeCannotBeNested() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);

        // Fail closed: checking the physical-attempt cap requires governed execution.
        assertThatThrownBy(() -> scope.permittedMaxAttempts(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No governed AI execution scope is active");

        assertThatThrownBy(() -> scope.execute(OPERATION, observer, 1, () -> {
            assertThat(scope.permittedMaxAttempts(3)).isEqualTo(1);
            assertThat(scope.permittedMaxAttempts(1)).isEqualTo(1);
            return scope.execute(OPERATION, observer,
                    () -> mock(AiChatResponse.class));
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nested");

        assertThat(scope.active()).isFalse();
        assertThatThrownBy(() -> scope.permittedMaxAttempts(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No governed AI execution scope is active");
        verifyNoInteractions(observer);
    }

    @Test
    void rejectsWrongOperationAndClearsScopeAfterFailure() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);
        UUID otherOperation = UUID.randomUUID();

        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.requireActive(otherOperation);
            return mock(AiChatResponse.class);
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");

        assertThat(scope.active()).isFalse();
        verifyNoInteractions(observer);
    }

    @Test
    void hardCapIsEnforcedEvenWhenAdapterBypassesRetryExecutor() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        var first = new AiProviderAttemptContext(OPERATION, UUID.randomUUID(), 1, 1);
        var second = new AiProviderAttemptContext(OPERATION, UUID.randomUUID(), 2, 2);
        var failure = rateLimited();
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, 1, () -> {
            scope.started(first);
            scope.beforeNetworkDispatch(first);
            scope.failed(first, failure);
            scope.started(second);
            return mock(AiChatResponse.class);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("cap exceeded");
        verify(observer).failed(first, failure);
        verify(observer, never()).started(second);
        verify(observer, never()).beforeNetworkDispatch(second);
        assertThat(scope.active()).isFalse();
    }

    @Test
    void capCannotBeInflatedInTheFirstAttemptContext() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, 1, () -> {
            scope.started(attempt(1));
            return mock(AiChatResponse.class);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("cap exceeded");
        verifyNoInteractions(observer);
    }

    @Test
    void successfulEvidenceCannotBeRecordedWithoutDispatchAuthorization() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        var first = attempt(1);
        var response = mock(AiChatResponse.class);
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.started(first);
            scope.succeeded(first, response);
            return response;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires dispatch authorization");
        verify(observer, never()).beforeNetworkDispatch(any());
        verify(observer, never()).succeeded(any(), any());
        verify(observer).ambiguous(eq(first), any(IllegalStateException.class));
    }

    @Test
    void duplicateDispatchIsRejectedAndCannotBeConvertedToSuccess() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        var first = attempt(1);
        var response = mock(AiChatResponse.class);
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.started(first);
            scope.beforeNetworkDispatch(first);
            assertThatThrownBy(() -> scope.beforeNetworkDispatch(first))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be reused");
            scope.succeeded(first, response);
            return response;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires dispatch authorization");
        verify(observer, times(1)).beforeNetworkDispatch(first);
        verify(observer, never()).succeeded(any(), any());
        verify(observer).ambiguous(eq(first), any());
    }

    @Test
    void onlyTheObservedResponseCanBeReturnedAndSuccessSealsExecution() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        var first = attempt(1);
        var second = attempt(2);
        var response = mock(AiChatResponse.class);
        var substituted = mock(AiChatResponse.class);
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.started(first);
            scope.beforeNetworkDispatch(first);
            scope.succeeded(first, response);
            assertThatThrownBy(() -> scope.started(second))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not permit");
            return substituted;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("matching successful physical evidence");
        verify(observer).succeeded(first, response);
        verify(observer, never()).started(second);
        verify(observer, never()).ambiguous(any(), any());
    }

    @Test
    void retryMustKeepOriginalLimitAndUseNewIdAfterKnownRejection() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        var first = attempt(1);
        var second = attempt(2);
        var response = mock(AiChatResponse.class);
        assertThat(scope.execute(OPERATION, observer, () -> {
            scope.started(first);
            scope.beforeNetworkDispatch(first);
            scope.failed(first, rateLimited());
            // Exceed the scope cap and change the declared limit independently.
            // Production checks the hard cap before the immutable retry limit.
            assertThatThrownBy(() -> scope.started(new AiProviderAttemptContext(
                    OPERATION, UUID.randomUUID(), 2, 4)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("physical attempt cap exceeded");
            assertThatThrownBy(() -> scope.started(new AiProviderAttemptContext(
                    OPERATION, UUID.randomUUID(), 2, 2)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("limit must remain unchanged");
            assertThatThrownBy(() -> scope.started(new AiProviderAttemptContext(
                    OPERATION, first.attemptId(), 2, 3)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ID must not be reused");
            scope.started(second);
            scope.beforeNetworkDispatch(second);
            scope.succeeded(second, response);
            return response;
        })).isSameAs(response);
        var order = inOrder(observer);
        order.verify(observer).started(first);
        order.verify(observer).beforeNetworkDispatch(first);
        order.verify(observer).failed(eq(first), any(AiProviderException.class));
        order.verify(observer).started(second);
        order.verify(observer).beforeNetworkDispatch(second);
        order.verify(observer).succeeded(second, response);
        verifyNoMoreInteractions(observer);
        assertThat(scope.active()).isFalse();
    }

    @Test
    void unacknowledgedFailureNeverPermitsAnotherAttempt() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        var first = attempt(1);
        var second = attempt(2);
        var failure = rateLimited();
        var lostAck = new IllegalStateException("lost acknowledgement");
        doThrow(lostAck).when(observer).failed(first, failure);
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.started(first);
            scope.beforeNetworkDispatch(first);
            assertThatThrownBy(() -> scope.failed(first, failure)).isSameAs(lostAck);
            scope.started(second);
            return mock(AiChatResponse.class);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("does not permit");
        verify(observer, never()).started(second);
        verify(observer, never()).ambiguous(any(), any());
        assertThat(scope.active()).isFalse();
    }

    @Test
    void ambiguousOutcomeNeverPermitsAnotherAttempt() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        var first = attempt(1);
        var second = attempt(2);
        var failure = new IllegalStateException("unknown network outcome");
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            scope.started(first);
            scope.beforeNetworkDispatch(first);
            scope.ambiguous(first, failure);
            scope.started(second);
            return mock(AiChatResponse.class);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("does not permit");
        verify(observer).ambiguous(first, failure);
        verify(observer, never()).started(second);
        verify(observer, never()).succeeded(any(), any());
    }

    @Test
    void lostStartedAckCannotBeRetriedEvenWhenExceptionIsCaught() {
        var scope = new AiExecutionAttemptScope();
        var observer = mock(AiExecutionAttemptObserver.class);
        var first = attempt(1);
        var lostAck = new IllegalStateException("lost acknowledgement");
        doThrow(lostAck).when(observer).started(first);
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, () -> {
            assertThatThrownBy(() -> scope.started(first)).isSameAs(lostAck);
            scope.started(attempt(1));
            return mock(AiChatResponse.class);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("does not permit");
        verify(observer).started(first);
        verifyNoMoreInteractions(observer);
    }

    private static AiProviderAttemptContext attempt(int number) {
        return new AiProviderAttemptContext(OPERATION, UUID.randomUUID(), number, 3);
    }

    private static AiProviderException rateLimited() {
        return new AiProviderException("openai", "gpt-5", 429, "req",
                AiProviderErrorType.RATE_LIMITED, true, false, null, "rate limited", null);
    }
}
