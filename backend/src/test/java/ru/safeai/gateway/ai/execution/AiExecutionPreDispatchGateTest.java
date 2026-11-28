package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagContextRevokedException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Проверяет порядок повторной авторизации непосредственно перед HTTP-адаптером. */
class AiExecutionPreDispatchGateTest {
    @Test
    void providerIsCalledOnlyAfterExplicitSecondAuthorization() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        UUID operation = UUID.randomUUID();
        AiProviderAttemptContext attempt = new AiProviderAttemptContext(operation, UUID.randomUUID(), 1, 1);
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);
        AiChatResponse response = mock(AiChatResponse.class);
        AtomicBoolean dispatched = new AtomicBoolean();

        scope.execute(operation, observer, () -> {
            scope.started(attempt);
            scope.beforeNetworkDispatch(attempt);
            dispatched.set(true);
            scope.succeeded(attempt, response);
            return response;
        });
        var order = inOrder(observer);
        order.verify(observer).started(attempt);
        order.verify(observer).beforeNetworkDispatch(attempt);
        order.verify(observer).succeeded(attempt, response);
        assertThat(dispatched).isTrue();
    }

    @Test
    void revokedContextPreventsActionFromRunningAndRecordsTerminalFailure() {
        AiExecutionAttemptScope scope = new AiExecutionAttemptScope();
        UUID operation = UUID.randomUUID();
        AiProviderAttemptContext attempt = new AiProviderAttemptContext(operation, UUID.randomUUID(), 1, 1);
        AiExecutionAttemptObserver observer = mock(AiExecutionAttemptObserver.class);
        doThrow(new KnowledgeRagContextRevokedException()).when(observer).beforeNetworkDispatch(attempt);
        when(observer.abortedBeforeDispatch(eq(attempt), any(KnowledgeRagContextRevokedException.class))).thenReturn(true);
        AtomicBoolean dispatched = new AtomicBoolean();

        assertThatThrownBy(() -> scope.execute(operation, observer, () -> {
            scope.started(attempt);
            scope.beforeNetworkDispatch(attempt);
            dispatched.set(true);
            return mock(AiChatResponse.class);
        })).isExactlyInstanceOf(AiDispatchAbortedException.class)
                .hasCauseInstanceOf(KnowledgeRagContextRevokedException.class);
        assertThat(dispatched).isFalse();
        verify(observer).abortedBeforeDispatch(eq(attempt), any(KnowledgeRagContextRevokedException.class));
        verify(observer, never()).ambiguous(any(), any());
    }

    @Test
    void missingAbortProofRequiresReconciliationAndSealsScope() {
        var scope = new AiExecutionAttemptScope();
        UUID operation = UUID.randomUUID();
        var attempt = new AiProviderAttemptContext(operation, UUID.randomUUID(), 1, 2);
        var observer = mock(AiExecutionAttemptObserver.class);
        var denied = new IllegalStateException("dispatch acknowledgement lost");
        doThrow(denied).when(observer).beforeNetworkDispatch(attempt);
        assertThatThrownBy(() -> scope.execute(operation, observer, () -> {
            scope.started(attempt);
            assertThatThrownBy(() -> scope.beforeNetworkDispatch(attempt))
                    .isInstanceOf(PhysicalAttemptReconciliationRequiredException.class)
                    .hasCause(denied);
            scope.started(new AiProviderAttemptContext(operation, UUID.randomUUID(), 2, 2));
            return mock(AiChatResponse.class);
        })).isInstanceOf(IllegalStateException.class);
        verify(observer, times(1)).started(any());
        verify(observer, never()).succeeded(any(), any());
        verify(observer, never()).failed(any(), any());
    }
}
