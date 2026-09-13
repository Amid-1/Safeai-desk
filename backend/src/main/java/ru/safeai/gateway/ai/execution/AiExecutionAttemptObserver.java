package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

public interface AiExecutionAttemptObserver {
    void started(AiProviderAttemptContext attempt);
    void succeeded(AiProviderAttemptContext attempt, AiChatResponse response);
    void failed(AiProviderAttemptContext attempt, AiProviderException exception);
    void ambiguous(AiProviderAttemptContext attempt, RuntimeException exception);
}
