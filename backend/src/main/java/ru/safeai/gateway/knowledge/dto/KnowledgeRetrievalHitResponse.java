package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit;

import java.util.UUID;

public record KnowledgeRetrievalHitResponse(
        int rank,
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

    public static KnowledgeRetrievalHitResponse from(
            int rank,
            KnowledgeRetrievalHit hit
    ) {
        return new KnowledgeRetrievalHitResponse(
                rank,
                hit.chunkId(),
                hit.documentId(),
                hit.documentVersionId(),
                hit.documentName(),
                hit.versionNumber(),
                hit.chunkOrdinal(),
                hit.content(),
                hit.pageFrom(),
                hit.pageTo(),
                hit.heading(),
                hit.fusedScore(),
                hit.lexicalRank(),
                hit.semanticRank(),
                hit.lexicalScore(),
                hit.cosineSimilarity(),
                hit.contentSha256()
        );
    }
}
