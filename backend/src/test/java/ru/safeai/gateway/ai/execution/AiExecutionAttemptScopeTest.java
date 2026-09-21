package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatResponse;
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
            scope.succeeded(attempt, response);
            assertThat(scope.hasActiveAttempt(attempt)).isFalse();
            return response;
        })).isSameAs(response);
        verify(observer).started(attempt);
        verify(observer).succeeded(attempt, response);
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
        AiChatResponse response = mock(AiChatResponse.class);
        assertThat(scope.execute(OPERATION, observer, () -> {
            scope.started(first);
            assertThatThrownBy(() -> scope.started(second))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not terminal");
            scope.failed(first, new AiProviderException("openai", "gpt-5", 429, "req",
                    ru.safeai.gateway.ai.exception.AiProviderErrorType.RATE_LIMITED,
                    true, false, null, "rate limited", null));
            assertThatThrownBy(() -> scope.started(first))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("sequentially");
            return response;
        })).isSameAs(response);
        verify(observer).started(first);
        verify(observer, never()).started(second);
        assertThat(scope.active()).isFalse();
    }

    @Test
    void hardCapIsVisibleToRetryExecutorAndScopeCannotBeNested() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);
        assertThat(scope.permittedMaxAttempts(3)).isEqualTo(3);
        assertThatThrownBy(() -> scope.execute(OPERATION, observer, 1, () -> {
            assertThat(scope.permittedMaxAttempts(3)).isEqualTo(1);
            return scope.execute(OPERATION, observer, () -> mock(AiChatResponse.class));
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Nested");
        assertThat(scope.active()).isFalse();
        assertThat(scope.permittedMaxAttempts(3)).isEqualTo(3);
    }

    private static AiProviderAttemptContext attempt(int number) {
        return new AiProviderAttemptContext(OPERATION, UUID.randomUUID(), number, 3);
    }
}
