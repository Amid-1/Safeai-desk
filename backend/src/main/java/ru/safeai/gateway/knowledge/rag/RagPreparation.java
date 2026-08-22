package ru.safeai.gateway.knowledge.rag;

import ru.safeai.gateway.ai.dto.AiChatRequest;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record RagPreparation(
        KnowledgeMode mode,
        UUID knowledgeBaseId,
        UUID retrievalRunId,
        String embeddingModel,
        String contextSha256,
        List<KnowledgeContextSource> sources,
        AiChatRequest aiRequest
) {

    private static final Pattern SHA256_HEX =
            Pattern.compile(
                    "[0-9a-f]{64}"
            );

    public RagPreparation {
        Objects.requireNonNull(
                mode,
                "mode не должен быть null"
        );

        Objects.requireNonNull(
                aiRequest,
                "aiRequest не должен быть null"
        );

        sources =
                sources == null
                        ? List.of()
                        : List.copyOf(
                        sources
                );

        if (mode == KnowledgeMode.GENERAL) {
            validateGeneralState(
                    knowledgeBaseId,
                    retrievalRunId,
                    embeddingModel,
                    contextSha256,
                    sources
            );
        } else {
            validateKnowledgeState(
                    knowledgeBaseId,
                    retrievalRunId,
                    embeddingModel,
                    contextSha256
            );
        }
    }

    public static RagPreparation general(
            AiChatRequest request
    ) {
        return new RagPreparation(
                KnowledgeMode.GENERAL,
                null,
                null,
                null,
                null,
                List.of(),
                request
        );
    }

    private static void validateGeneralState(
            UUID knowledgeBaseId,
            UUID retrievalRunId,
            String embeddingModel,
            String contextSha256,
            List<KnowledgeContextSource> sources
    ) {
        if (knowledgeBaseId != null
                || retrievalRunId != null
                || embeddingModel != null
                || contextSha256 != null
                || !sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "GENERAL RagPreparation не должен содержать knowledge state"
            );
        }
    }

    private static void validateKnowledgeState(
            UUID knowledgeBaseId,
            UUID retrievalRunId,
            String embeddingModel,
            String contextSha256
    ) {
        Objects.requireNonNull(
                knowledgeBaseId,
                "knowledgeBaseId обязателен для knowledge mode"
        );

        Objects.requireNonNull(
                retrievalRunId,
                "retrievalRunId обязателен для knowledge mode"
        );

        requireEmbeddingModel(
                embeddingModel
        );

        requireSha256(
                contextSha256
        );
    }

    private static void requireEmbeddingModel(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            throw new IllegalArgumentException(
                    "embeddingModel обязателен для knowledge mode"
            );
        }
    }

    private static void requireSha256(
            String value
    ) {
        if (value == null
                || !SHA256_HEX.matcher(
                value
        ).matches()) {
            throw new IllegalArgumentException(
                    "contextSha256 должен быть SHA-256 hex"
            );
        }
    }
}
