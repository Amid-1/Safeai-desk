package ru.safeai.gateway.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeReindexResponse(
        UUID knowledgeBaseId,
        UUID documentId,
        UUID documentVersionId,
        UUID ingestionJobId,
        String status,
        Instant requestedAt
) {
}
