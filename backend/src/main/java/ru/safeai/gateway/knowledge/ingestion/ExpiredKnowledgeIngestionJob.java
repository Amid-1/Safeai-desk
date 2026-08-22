package ru.safeai.gateway.knowledge.ingestion;

import java.util.UUID;

public record ExpiredKnowledgeIngestionJob(
        UUID jobId,
        UUID organizationId,
        UUID knowledgeBaseId,
        UUID documentId,
        UUID documentVersionId,
        int attempt
) {
}
