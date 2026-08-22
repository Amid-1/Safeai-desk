package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeDocumentVersionResponse(
        UUID id,
        UUID documentId,
        int versionNumber,
        String originalFilename,
        String mediaType,
        long sizeBytes,
        String sha256,
        UUID createdByUserId,
        Instant createdAt
) {
    public static KnowledgeDocumentVersionResponse from(
            KnowledgeDocumentVersionEntity entity
    ) {
        return new KnowledgeDocumentVersionResponse(
                entity.getId(),
                entity.getDocumentId(),
                entity.getVersionNumber(),
                entity.getOriginalFilename(),
                entity.getMediaType(),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getCreatedByUserId(),
                entity.getCreatedAt()
        );
    }
}
