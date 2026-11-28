package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

public interface AiExecutionAttemptObserver {
    void started(AiProviderAttemptContext attempt);
    /**
     * Последняя проверка доступа перед физическим вызовом провайдера.
     * Обязательна для каждого наблюдателя, независимо от Knowledge-контекста.
     */
    void beforeNetworkDispatch(AiProviderAttemptContext attempt);

    /** No adapter invocation by this caller; durable receipt absence must still be proven. */
    default boolean abortedBeforeDispatch(AiProviderAttemptContext attempt, RuntimeException reason) {
        // Legacy observers must retain uncertainty, not invent a non-executed result.
        ambiguous(attempt, reason);
        return false;
    }

    void succeeded(AiProviderAttemptContext attempt, AiChatResponse response);
    void failed(AiProviderAttemptContext attempt, AiProviderException exception);
    void ambiguous(AiProviderAttemptContext attempt, RuntimeException exception);
}
