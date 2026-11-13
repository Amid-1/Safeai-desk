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
        AtomicBoolean dispatched = new AtomicBoolean();

        assertThatThrownBy(() -> scope.execute(operation, observer, () -> {
            scope.started(attempt);
            scope.beforeNetworkDispatch(attempt);
            dispatched.set(true);
            return mock(AiChatResponse.class);
        })).isExactlyInstanceOf(KnowledgeRagContextRevokedException.class);
        assertThat(dispatched).isFalse();
        verify(observer).ambiguous(eq(attempt), any(KnowledgeRagContextRevokedException.class));
    }
}
