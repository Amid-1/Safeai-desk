package ru.safeai.gateway.knowledge.config;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class KnowledgeIngestionPropertiesConcurrencyTest {
    @Test
    void preservesLegacySingleWorkerDefault() {
        KnowledgeIngestionProperties p = new KnowledgeIngestionProperties(
                true, Duration.ofSeconds(2), 4, Duration.ofMinutes(3),
                Duration.ofSeconds(60), 2, 5, Duration.ofSeconds(10),
                Duration.ofMinutes(10), 2000000, 104857600L, 1200, 150);
        assertThat(p.workerThreads()).isEqualTo(1);
    }

    @Test
    void refusesExcessiveWorkerConcurrency() {
        assertThatThrownBy(() -> new KnowledgeIngestionProperties(
                true, Duration.ofSeconds(2), 4, Duration.ofMinutes(3),
                Duration.ofSeconds(60), 2, 5, Duration.ofSeconds(10),
                Duration.ofMinutes(10), 2000000, 104857600L, 1200, 150, 100))
                .isInstanceOf(IllegalStateException.class);
    }
}
