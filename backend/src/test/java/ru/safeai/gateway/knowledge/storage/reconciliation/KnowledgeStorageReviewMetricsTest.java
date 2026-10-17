package ru.safeai.gateway.knowledge.storage.reconciliation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeStorageReviewMetricsTest {
    @Test
    void reportsPendingReviewAndNeverHidesDatabaseFailureAsZero() {
        KnowledgeStorageUploadJournal journal = mock(KnowledgeStorageUploadJournal.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KnowledgeStorageReviewMetrics metrics = new KnowledgeStorageReviewMetrics(journal, registry);
        when(journal.countNeedsReview()).thenReturn(3L)
                .thenThrow(new IllegalStateException("SECRET_SQL_MESSAGE"));
        metrics.refresh();
        assertThat(registry.get("safeai.knowledge.storage.needs.review.rows")
                .gauge().value()).isEqualTo(3.0);
        metrics.refresh();
        assertThat(registry.get("safeai.knowledge.storage.needs.review.rows")
                .gauge().value()).isEqualTo(-1.0);
        assertThat(registry.get("safeai.knowledge.storage.review.metrics.failures")
                .counter().count()).isEqualTo(1.0);
        registry.close();
    }
}
