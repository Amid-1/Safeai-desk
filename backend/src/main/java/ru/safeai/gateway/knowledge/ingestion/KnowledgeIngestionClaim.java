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
        Instant leaseUntil,
        UUID correlationId
) {
    public KnowledgeIngestionClaim(UUID jobId, UUID organizationId, UUID knowledgeBaseId,
                                   UUID documentId, UUID documentVersionId, UUID processingToken,
                                   int attempt, Instant leaseUntil) {
        this(jobId, organizationId, knowledgeBaseId, documentId, documentVersionId,
                processingToken, attempt, leaseUntil, null);
    }
}
