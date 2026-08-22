package ru.safeai.gateway.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeHealthResponse(
        UUID knowledgeBaseId,
        String state,
        String activeEmbeddingModel,
        long totalDocuments,
        long enabledDocuments,
        long searchableDocuments,
        long pendingDocuments,
        long processingDocuments,
        long failedDocuments,
        long staleEmbeddingDocuments,
        long activeChunks,
        Instant checkedAt
) {
}
