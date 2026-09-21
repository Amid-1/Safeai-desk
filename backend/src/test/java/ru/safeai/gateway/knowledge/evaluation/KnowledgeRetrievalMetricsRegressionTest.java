package ru.safeai.gateway.knowledge.evaluation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class KnowledgeRetrievalMetricsRegressionTest {
    private final UUID relevant = UUID.randomUUID();
    private final UUID another = UUID.randomUUID();
    private final UUID irrelevant = UUID.randomUUID();

    @Test
    void perfectRankingHasExactUnitMetrics() {
        var result = KnowledgeRetrievalMetrics.evaluate(List.of(relevant, another),
                Set.of(relevant, another));
        assertThat(result.recall()).isEqualTo(1d);
        assertThat(result.reciprocalRank()).isEqualTo(1d);
        assertThat(result.ndcg()).isEqualTo(1d);
        assertThat(result.firstRelevantRank()).isEqualTo(1);
    }

    @Test
    void duplicateRankedVersionNeverInflatesRecallOrDcg() {
        var result = KnowledgeRetrievalMetrics.evaluate(List.of(relevant, relevant, another),
                Set.of(relevant, another));
        assertThat(result.recall()).isEqualTo(1d);
        assertThat(result.ndcg()).isLessThanOrEqualTo(1d);
        assertThat(result.firstRelevantRank()).isEqualTo(1);
    }

    @Test
    void noRelevantResultsHasAllZeroMetrics() {
        var result = KnowledgeRetrievalMetrics.evaluate(List.of(irrelevant), Set.of(relevant));
        assertThat(result.recall()).isZero();
        assertThat(result.reciprocalRank()).isZero();
        assertThat(result.ndcg()).isZero();
        assertThat(result.firstRelevantRank()).isZero();
    }

    @Test
    void emptyRankingIsSafeAndDoesNotInventRecall() {
        var result = KnowledgeRetrievalMetrics.evaluate(List.of(), Set.of(relevant));
        assertThat(result.recall()).isZero();
        assertThat(result.reciprocalRank()).isZero();
        assertThat(result.ndcg()).isZero();
    }

    @Test
    void emptyGoldSetIsInvalidEvaluationConfiguration() {
        assertThatThrownBy(() -> KnowledgeRetrievalMetrics.evaluate(List.of(relevant), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
