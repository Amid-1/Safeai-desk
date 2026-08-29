package ru.safeai.gateway.knowledge.dto;

import ru.safeai.gateway.knowledge.rag.KnowledgeMode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AnswerPassportResponse(
        UUID id,
        UUID chatTurnId,
        UUID retrievalRunId,
        UUID knowledgeBaseId,
        UUID modelRouteDecisionId,
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
        Objects.requireNonNull(id, "id не должен быть null");
        Objects.requireNonNull(chatTurnId, "chatTurnId не должен быть null");
        Objects.requireNonNull(retrievalRunId, "retrievalRunId не должен быть null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId не должен быть null");
        // Nullable only for historical V1–V44 rows read after V45 migration.
        Objects.requireNonNull(knowledgeMode, "knowledgeMode не должен быть null");
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
