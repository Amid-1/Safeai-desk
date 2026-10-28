package ru.safeai.gateway.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

/** DELETING means accepted, not physically erased. */
public record KnowledgeDocumentDeletionResponse(
        UUID operationId,
        UUID documentId,
        String state,
        Instant createdAt,
        Instant completedAt
) { }
