package ru.safeai.gateway.knowledge.config;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeOcrSafetyMarginTest {
    @Test
    void httpOcrMustLeaveTimeForNativePdfExtractionAndResultValidation() {
        var ocr = new KnowledgeOcrProperties("http", "https://ocr.example.org", "key",
                List.of("ocr.example.org"), 20,
                Duration.ofSeconds(1), Duration.ofSeconds(20), 1_000_000L);
        assertThatThrownBy(() ->
                new KnowledgeOcrConfigurationInvariantVerifier(ocr, ingestion(Duration.ofSeconds(26))).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safety margin");
        new KnowledgeOcrConfigurationInvariantVerifier(ocr, ingestion(Duration.ofSeconds(27))).verify();
    }

    private static KnowledgeIngestionProperties ingestion(Duration extractionTimeout) {
        return new KnowledgeIngestionProperties(
                true, Duration.ofSeconds(2), 4, Duration.ofMinutes(3),
                extractionTimeout, 2, 5, Duration.ofSeconds(10), Duration.ofMinutes(10),
                2_000_000, 100L * 1024L * 1024L, 1200, 150);
    }
}
