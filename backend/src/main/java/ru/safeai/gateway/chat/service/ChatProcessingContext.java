package ru.safeai.gateway.chat.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable processing identity carried across provider I/O and finalization.
 *
 * <p>{@code modelRouteDecisionId} is non-null for every V45+ newly reserved
 * turn. Null is tolerated only when reading historical/replay state created
 * before the V45 Model Control Plane.</p>
 */
public record ChatProcessingContext(
        UUID chatId,
        UUID turnId,
        UUID userMessageId,
        UUID clientRequestId,
        UUID providerOperationId,
        UUID processingToken,
        Instant leaseUntil,
        UUID modelRouteDecisionId,
        AiChatRequest aiRequest,
        UUID knowledgeBaseId,
        KnowledgeMode knowledgeMode,
        boolean replay
) {

    public ChatProcessingContext {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        Objects.requireNonNull(
                turnId,
                "turnId не должен быть null"
        );

        Objects.requireNonNull(
                userMessageId,
                "userMessageId не должен быть null"
        );

        Objects.requireNonNull(
                clientRequestId,
                "clientRequestId не должен быть null"
        );

        Objects.requireNonNull(
                providerOperationId,
                "providerOperationId не должен быть null"
        );

        knowledgeMode =
                knowledgeMode == null
                        ? KnowledgeMode.GENERAL
                        : knowledgeMode;

        validateKnowledgeScope(
                knowledgeBaseId,
                knowledgeMode
        );

        if (replay) {
            if (
                    processingToken != null
                            || leaseUntil != null
                            || aiRequest != null
            ) {
                throw new IllegalArgumentException(
                        "Replay context не может содержать processing metadata"
                );
            }
        } else {
            Objects.requireNonNull(
                    processingToken,
                    "processingToken не должен быть null"
            );

            Objects.requireNonNull(
                    leaseUntil,
                    "leaseUntil не должен быть null"
            );

            Objects.requireNonNull(
                    aiRequest,
                    "aiRequest не должен быть null"
            );

            if (
                    !providerOperationId.equals(
                            aiRequest.providerOperationId()
                    )
            ) {
                throw new IllegalArgumentException(
                        "providerOperationId context и AI request не совпадают"
                );
            }
        }
    }

    /**
     * V45 convenience constructor without knowledge scope.
     */
    public ChatProcessingContext(
            UUID chatId,
            UUID turnId,
            UUID userMessageId,
            UUID clientRequestId,
            UUID providerOperationId,
            UUID processingToken,
            Instant leaseUntil,
            UUID modelRouteDecisionId,
            AiChatRequest aiRequest,
            boolean replay
    ) {
        this(
                chatId,
                turnId,
                userMessageId,
                clientRequestId,
                providerOperationId,
                processingToken,
                leaseUntil,
                modelRouteDecisionId,
                aiRequest,
                null,
                KnowledgeMode.GENERAL,
                replay
        );
    }

    /**
     * Source-compatibility overload for V44 tests/fixtures.
     *
     * <p>Production V45 reservation code must not use this constructor because
     * it cannot carry {@code modelRouteDecisionId}.</p>
     *
     * @deprecated use the V45 constructor that explicitly accepts
     * {@code modelRouteDecisionId}
     */
    @Deprecated
    public ChatProcessingContext(
            UUID chatId,
            UUID turnId,
            UUID userMessageId,
            UUID clientRequestId,
            UUID providerOperationId,
            UUID processingToken,
            Instant leaseUntil,
            AiChatRequest aiRequest,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode,
            boolean replay
    ) {
        this(
                chatId,
                turnId,
                userMessageId,
                clientRequestId,
                providerOperationId,
                processingToken,
                leaseUntil,
                null,
                aiRequest,
                knowledgeBaseId,
                knowledgeMode,
                replay
        );
    }

    public ChatProcessingContext withAiRequest(
            AiChatRequest request
    ) {
        if (replay) {
            throw new IllegalStateException(
                    "Replay context нельзя изменять"
            );
        }

        return new ChatProcessingContext(
                chatId,
                turnId,
                userMessageId,
                clientRequestId,
                providerOperationId,
                processingToken,
                leaseUntil,
                modelRouteDecisionId,
                request,
                knowledgeBaseId,
                knowledgeMode,
                false
        );
    }

    private static void validateKnowledgeScope(
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        if (knowledgeMode.usesKnowledge()) {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException(
                        "Некорректная knowledge scope у ChatProcessingContext"
                );
            }

            return;
        }

        if (knowledgeBaseId != null) {
            throw new IllegalArgumentException(
                    "Некорректная knowledge scope у ChatProcessingContext"
            );
        }
    }
}