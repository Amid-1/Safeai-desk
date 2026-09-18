package ru.safeai.gateway.ai.provider;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;

public interface AiProvider {

    AiChatResponse sendMessage(AiChatRequest request);

    /**
     * Returns the exact immutable identity of the currently configured
     * physical target. The single-provider runtime is intentionally strict:
     * routing cannot manufacture a different target and discover the
     * mismatch only after provider I/O.
     */
    ProviderExecutionTarget executionTarget();

    /**
     * Declares that the adapter reports every physical provider attempt
     * through {@link AiProviderRetryExecutor}. The execution service checks
     * this before provider I/O so an adapter cannot silently bypass the
     * durable attempt ledger.
     */
    default boolean recordsPhysicalAttempts() {
        return false;
    }
}
