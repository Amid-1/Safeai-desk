package ru.safeai.gateway.knowledge.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KnowledgeEvaluationResponse(
        UUID evaluationRunId,
        UUID knowledgeBaseId,
        String datasetName,
        int topK,
        double meanRecall,
        double meanReciprocalRank,
        double meanNdcg,
        Instant createdAt,
        List<KnowledgeEvaluationCaseResponse> cases
) {
    public KnowledgeEvaluationResponse {
        cases = List.copyOf(cases);
    }
}
