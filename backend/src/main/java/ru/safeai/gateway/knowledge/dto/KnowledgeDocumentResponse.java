package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeDocumentResponse(
        UUID id, UUID knowledgeBaseId,
        String name,
        boolean enabled,
        long version,
        UUID currentVersionId,
        Integer versionNumber,
        String originalFilename,
        String mediaType,
        long sizeBytes,
        KnowledgeIngestionStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public static KnowledgeDocumentResponse from(
            KnowledgeDocumentEntity d,
            KnowledgeDocumentVersionEntity v,
            KnowledgeIngestionStatus status) {
        return new KnowledgeDocumentResponse(
                d.getId(),
                d.getKnowledgeBaseId(),
                d.getName(),
                d.isEnabled(),
                d.getVersion(),
                d.getCurrentVersionId(),
                v == null ? null : v.getVersionNumber(),
                v == null ? null : v.getOriginalFilename(),
                v == null ? null : v.getMediaType(),
                v == null ? 0 : v.getSizeBytes(),
                status, d.getCreatedAt(),
                d.getUpdatedAt());
    }
}
