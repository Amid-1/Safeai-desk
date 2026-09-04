package ru.safeai.gateway.knowledge.chunking;

/**
 * Immutable chunk candidate produced by deterministic character-boundary
 * chunking.
 *
 * <p>{@code estimatedTokens} is an informational retrieval/chunking heuristic
 * only. It is not a conservative model-governance bound and MUST NOT be used
 * for routing reservation or provider input limits. Those boundaries use the
 * shared AI input-unit estimator.</p>
 */
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
