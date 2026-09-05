package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeReindexResponse(
        UUID knowledgeBaseId,
        UUID documentId,
        UUID documentVersionId,
        UUID ingestionJobId,
        KnowledgeIngestionStatus status,
        Instant requestedAt
) {
}
