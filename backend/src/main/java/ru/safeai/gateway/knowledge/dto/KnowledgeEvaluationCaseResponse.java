package ru.safeai.gateway.knowledge.dto;

import java.util.UUID;

public record KnowledgeEvaluationCaseResponse(
        int ordinal,
        UUID retrievalRunId,
        double recall,
        double reciprocalRank,
        double ndcg,
        int firstRelevantRank
) {
}
