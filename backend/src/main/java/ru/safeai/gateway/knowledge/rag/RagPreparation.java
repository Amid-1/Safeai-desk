package ru.safeai.gateway.knowledge.rag;

import ru.safeai.gateway.ai.dto.AiChatRequest;

import java.util.List;
import java.util.UUID;

public record RagPreparation(
        KnowledgeMode mode,
        UUID knowledgeBaseId,
        UUID retrievalRunId,
        String embeddingModel,
        String contextSha256,
        List<KnowledgeContextSource> sources,
        AiChatRequest aiRequest
) {
    public RagPreparation {
        sources = List.copyOf(sources);
    }

    public static RagPreparation general(AiChatRequest request) {
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

    public boolean usesKnowledge() {
        return mode.usesKnowledge();
    }
}
