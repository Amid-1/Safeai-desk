package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeBaseResponse(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        KnowledgeBaseVisibility visibility,
        boolean enabled,
        UUID createdByUserId,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static KnowledgeBaseResponse from(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseResponse(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getName(),
                entity.getDescription(),
                entity.getVisibility(),
                entity.isEnabled(),
                entity.getCreatedByUserId(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
