package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.rag.KnowledgeMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnswerPassportResponse(
        UUID id,
        UUID chatTurnId,
        UUID retrievalRunId,
        UUID knowledgeBaseId,
        KnowledgeMode knowledgeMode,
        String provider,
        String requestedModel,
        String resolvedModel,
        String embeddingModel,
        String contextSha256,
        String answerSha256,
        boolean evidenceSufficient,
        boolean citationsValid,
        Instant createdAt,
        List<AnswerCitationResponse> citations
) {
    public AnswerPassportResponse {
        citations = List.copyOf(citations);
    }
}
