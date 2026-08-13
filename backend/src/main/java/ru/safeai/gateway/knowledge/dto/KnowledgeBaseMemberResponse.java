package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeBaseMemberResponse(
        UUID knowledgeBaseId,
        UUID userId,
        String email,
        String fullName,
        KnowledgeBaseAccessLevel accessLevel,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
