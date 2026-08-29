package ru.safeai.gateway.chat.service;

import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.audit.details.ChatTurnAuditDetails;
import ru.safeai.gateway.chat.entity.ChatTurnState;

import java.util.UUID;

/** Creates immutable audit payloads for terminal chat-turn transitions. */
final class ChatTurnAuditDetailsFactory {

    private ChatTurnAuditDetailsFactory() {
    }

    static ChatTurnAuditDetails success(
            ChatProcessingContext context,
            UUID assistantMessageId,
            AiChatResponse response,
            String provider
    ) {
        return new ChatTurnAuditDetails(
                context.chatId(),
                context.turnId(),
                context.userMessageId(),
                assistantMessageId,
                context.clientRequestId(),
                context.providerOperationId(),
                ChatTurnState.SUCCEEDED.name(),
                provider,
                response.requestedModel(),
                response.model(),
                response.providerRequestId(),
                null,
                null,
                false,
                response.inputTokens(),
                response.outputTokens(),
                response.costUsd(),
                response.usageStatus().name(),
                response.pricingStatus().name(),
                response.currency()
        );
    }

    static ChatTurnAuditDetails failure(
            ChatProcessingContext context,
            String provider,
            String requestedModel,
            String providerRequestId,
            String providerErrorType,
            String failureCode,
            boolean ambiguous
    ) {
        return new ChatTurnAuditDetails(
                context.chatId(),
                context.turnId(),
                context.userMessageId(),
                null,
                context.clientRequestId(),
                context.providerOperationId(),
                ambiguous
                        ? ChatTurnState.AMBIGUOUS.name()
                        : ChatTurnState.FAILED.name(),
                provider,
                requestedModel,
                null,
                providerRequestId,
                providerErrorType,
                failureCode,
                ambiguous,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
