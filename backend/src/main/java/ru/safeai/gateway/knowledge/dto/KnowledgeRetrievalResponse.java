package ru.safeai.gateway.knowledge.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KnowledgeRetrievalResponse(
        UUID retrievalRunId,
        UUID knowledgeBaseId,
        String querySha256,
        String embeddingModel,
        Instant completedAt,
        List<KnowledgeRetrievalHitResponse> hits
) {

    public KnowledgeRetrievalResponse {
        hits = List.copyOf(hits);
    }
}
