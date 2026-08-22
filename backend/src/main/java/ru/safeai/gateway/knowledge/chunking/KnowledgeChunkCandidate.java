package ru.safeai.gateway.knowledge.chunking;

public record KnowledgeChunkCandidate(
        int ordinal,
        String content,
        String contentSha256,
        int estimatedTokens,
        Integer pageFrom,
        Integer pageTo,
        String heading
) {
}
