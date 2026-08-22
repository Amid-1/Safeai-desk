package ru.safeai.gateway.knowledge.dto;

import java.util.UUID;

public record AnswerCitationResponse(
        String label,
        UUID chunkId,
        UUID documentId,
        UUID documentVersionId,
        String documentName,
        int versionNumber,
        int chunkOrdinal,
        Integer pageFrom,
        Integer pageTo,
        String heading,
        String contentSha256
) {
}
