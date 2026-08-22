package ru.safeai.gateway.knowledge.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KnowledgeRetrievalExecution(
        UUID retrievalRunId,
        UUID knowledgeBaseId,
        UUID chatTurnId,
        String querySha256,
        String embeddingModel,
        Instant completedAt,
        List<KnowledgeRetrievalHit> hits
) {
    public KnowledgeRetrievalExecution {
        hits = List.copyOf(hits);
    }
}
