package ru.safeai.gateway.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

/** Never includes the deleted base's name, description or document contents. */
public record KnowledgeBaseDeletionResponse(
        UUID operationId,
        UUID knowledgeBaseId,
        String state,
        Instant createdAt,
        Instant completedAt,
        String errorCode
) { }
