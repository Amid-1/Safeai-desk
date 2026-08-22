package ru.safeai.gateway.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRetrievalMetricsTest {

    @Test
    void computesRecallMrrAndNdcgWithoutDoubleCountingVersion() {
        UUID relevantA = UUID.randomUUID();
        UUID relevantB = UUID.randomUUID();
        UUID irrelevant = UUID.randomUUID();

        var result = KnowledgeRetrievalMetrics.evaluate(
                List.of(irrelevant, relevantA, relevantA, relevantB),
                Set.of(relevantA, relevantB)
        );

        assertThat(result.recall()).isEqualTo(1.0);
        assertThat(result.reciprocalRank()).isEqualTo(0.5);
        assertThat(result.firstRelevantRank()).isEqualTo(2);
        assertThat(result.ndcg()).isBetween(0.0, 1.0);
    }
}
