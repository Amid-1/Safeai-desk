package ru.safeai.gateway.knowledge.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class KnowledgeRetrievalMetrics {

    private KnowledgeRetrievalMetrics() {
    }

    public static Result evaluate(
            List<UUID> rankedDocumentVersionIds,
            Set<UUID> expectedDocumentVersionIds
    ) {
        if (expectedDocumentVersionIds.isEmpty()) {
            throw new IllegalArgumentException("Expected set must not be empty");
        }
        Set<UUID> seenRelevant = new HashSet<>();
        int firstRelevantRank = 0;
        double dcg = 0.0;
        for (int index = 0; index < rankedDocumentVersionIds.size(); index++) {
            UUID candidate = rankedDocumentVersionIds.get(index);
            if (expectedDocumentVersionIds.contains(candidate)) {
                if (firstRelevantRank == 0) {
                    firstRelevantRank = index + 1;
                }
                if (seenRelevant.add(candidate)) {
                    dcg += 1.0 / log2(index + 2.0);
                }
            }
        }
        double idealDcg = 0.0;
        int idealCount = Math.min(
                expectedDocumentVersionIds.size(),
                rankedDocumentVersionIds.size()
        );
        for (int index = 0; index < idealCount; index++) {
            idealDcg += 1.0 / log2(index + 2.0);
        }
        return new Result(
                (double) seenRelevant.size() / expectedDocumentVersionIds.size(),
                firstRelevantRank == 0 ? 0.0 : 1.0 / firstRelevantRank,
                idealDcg == 0.0 ? 0.0 : dcg / idealDcg,
                firstRelevantRank
        );
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    public record Result(
            double recall,
            double reciprocalRank,
            double ndcg,
            int firstRelevantRank
    ) {
    }
}
