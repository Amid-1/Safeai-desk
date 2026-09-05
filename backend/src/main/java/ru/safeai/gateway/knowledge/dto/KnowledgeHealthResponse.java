package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.model.KnowledgeHealthState;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeHealthResponse(
        UUID knowledgeBaseId,
        KnowledgeHealthState state,
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
