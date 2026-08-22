package ru.safeai.gateway.knowledge.ingestion;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeIngestionClaim(
        UUID jobId,
        UUID organizationId,
        UUID knowledgeBaseId,
        UUID documentId,
        UUID documentVersionId,
        UUID processingToken,
        int attempt,
        Instant leaseUntil
) {
}
