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
        AiChatRequest aiRequest,
        UUID executionPermitId
) {
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /** Source-compatible constructor for non-production tests and legacy GENERAL contexts. */
    public RagPreparation(
            KnowledgeMode mode,
            UUID knowledgeBaseId,
            UUID retrievalRunId,
            String embeddingModel,
            String contextSha256,
            List<KnowledgeContextSource> sources,
            AiChatRequest aiRequest
    ) {
        this(mode, knowledgeBaseId, retrievalRunId, embeddingModel, contextSha256,
                sources, aiRequest, null);
    }

    public RagPreparation {
        Objects.requireNonNull(mode, "mode не должен быть null");
        Objects.requireNonNull(aiRequest, "aiRequest не должен быть null");
        sources = sources == null ? List.of() : List.copyOf(sources);

        if (mode == KnowledgeMode.GENERAL) {
            if (knowledgeBaseId != null || retrievalRunId != null
                    || embeddingModel != null || contextSha256 != null
                    || !sources.isEmpty() || executionPermitId != null) {
                throw new IllegalArgumentException(
                        "GENERAL RagPreparation не должен содержать knowledge state");
            }
        } else {
            Objects.requireNonNull(knowledgeBaseId,
                    "knowledgeBaseId обязателен для knowledge mode");
            Objects.requireNonNull(retrievalRunId,
                    "retrievalRunId обязателен для knowledge mode");
            if (embeddingModel == null || embeddingModel.isBlank()) {
                throw new IllegalArgumentException(
                        "embeddingModel обязателен для knowledge mode");
            }
            if (contextSha256 == null || !SHA256_HEX.matcher(contextSha256).matches()) {
                throw new IllegalArgumentException(
                        "contextSha256 должен быть SHA-256 hex");
            }
        }
    }

    public static RagPreparation general(AiChatRequest request) {
        return new RagPreparation(KnowledgeMode.GENERAL, null, null, null,
                null, List.of(), request, null);
    }

    public RagPreparation withExecutionPermit(UUID permitId) {
        if (!mode.usesKnowledge() || permitId == null) {
            throw new IllegalArgumentException("Execution permit requires Knowledge mode");
        }
        return new RagPreparation(mode, knowledgeBaseId, retrievalRunId, embeddingModel,
                contextSha256, sources, aiRequest, permitId);
    }
}
