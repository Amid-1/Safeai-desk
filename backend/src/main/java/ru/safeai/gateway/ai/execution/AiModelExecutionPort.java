package ru.safeai.gateway.ai.execution;

import ru.safeai.gateway.ai.dto.AiChatRequest;

import java.util.UUID;

/**
 * Fail-closed bridge to persisted Model Control Plane decisions and receipts.
 * The implementation must validate durable evidence before a physical call.
 */
public interface AiModelExecutionPort {

    AiExecutionIdentity bindExecution(
            UUID decisionId,
            UUID chatTurnId,
            UUID clientRequestId,
            UUID providerOperationId,
            AiChatRequest reserved,
            UUID knowledgeBaseId,
            AiKnowledgeExecutionMode knowledgeMode
    );

    void authorizePhysicalExecution(
            AiExecutionIdentity identity,
            UUID chatTurnId,
            UUID modelRouteDecisionId,
            AiChatRequest reserved,
            AiChatRequest prepared,
            String targetProvider,
            String targetModel
    );

    boolean requiresSinglePhysicalAttempt(UUID decisionId);
}
