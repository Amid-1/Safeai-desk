package ru.safeai.gateway.knowledge.retrieval;

import java.util.UUID;

public record KnowledgeRetrievalHit(
        UUID chunkId,
        UUID documentId,
        UUID documentVersionId,
        String documentName,
        int versionNumber,
        int chunkOrdinal,
        String content,
        Integer pageFrom,
        Integer pageTo,
        String heading,
        double fusedScore,
        Integer lexicalRank,
        Integer semanticRank,
        Float lexicalScore,
        Float cosineSimilarity,
        String contentSha256
) {
}
