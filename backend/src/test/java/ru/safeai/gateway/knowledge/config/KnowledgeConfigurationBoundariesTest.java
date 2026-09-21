package ru.safeai.gateway.knowledge.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.safeai.gateway.knowledge.embedding.HashingKnowledgeEmbeddingProvider;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class KnowledgeConfigurationBoundariesTest {
    @Test
    void disabledOcrDropsUnexpectedOutboundSecretsAndHosts() {
        var properties = new KnowledgeOcrProperties("disabled", "https://evil.example/ocr", "secret",
                List.of("evil.example"), 20, null, null, null);
        assertThat(properties.endpoint()).isNull();
        assertThat(properties.apiKey()).isNull();
        assertThat(properties.allowedHosts()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://ocr.safeai.test/api", "https://other.safeai.test/api",
            "https://user:pass@ocr.safeai.test/api", "https://ocr.safeai.test/api?secret=x",
            "https://ocr.safeai.test/api#fragment"
    })
    void outboundOcrBoundaryRequiresHttpsAndExplicitExactHost(String url) {
        assertThatThrownBy(() -> ocr("http", url, "secret", List.of("ocr.safeai.test")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void permittedOcrEndpointNormalizesHostAndRedactsKey() {
        var properties = ocr("HTTP", "https://ocr.safeai.test/api", "sensitive-credential",
                List.of(" OCR.SAFEAI.TEST ", "ocr.safeai.test"));
        assertThat(properties.provider()).isEqualTo("http");
        assertThat(properties.allowedHosts()).containsExactly("ocr.safeai.test");
        assertThat(properties.toString()).doesNotContain("sensitive-credential");
    }

    @Test
    void localEmbeddingMustDiscardProviderCredentials() {
        var properties = new KnowledgeEmbeddingProperties("hashing", "https://api.openai.com/v1",
                "should-not-survive", "unknown-model", 384, 2, 1_000,
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(properties.apiKey()).isNull();
        assertThat(properties.baseUrl()).isNull();
        assertThat(properties.model()).isEqualTo(HashingKnowledgeEmbeddingProvider.MODEL);
    }

    @Test
    void ocrDeadlineCannotExceedExtractionDeadline() {
        var properties = ocr("http", "https://ocr.safeai.test/api", "secret", List.of("ocr.safeai.test"));
        var ingestion = ingestion(Duration.ofMinutes(2), Duration.ofSeconds(2));
        assertThatThrownBy(new KnowledgeOcrConfigurationInvariantVerifier(properties, ingestion)::verify)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("extraction-timeout");
    }

    @Test
    void embeddingBudgetWithSafetyMarginMustFitLease() {
        var properties = new KnowledgeEmbeddingProperties("openai", null, "credential", null,
                384, 2, 1_000, Duration.ofSeconds(1), Duration.ofSeconds(2));
        var ingestion = ingestion(Duration.ofSeconds(7), Duration.ofSeconds(2));
        assertThatThrownBy(new KnowledgeEmbeddingConfigurationInvariantVerifier(properties, ingestion)::verify)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("processing-lease");
    }

    @Test
    void exponentialBackoffCapsAtConfiguredMaximum() {
        var ingestion = ingestion(Duration.ofMinutes(3), Duration.ofSeconds(10));
        assertThat(ingestion.backoffForAttempt(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(ingestion.backoffForAttempt(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(ingestion.backoffForAttempt(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(ingestion.backoffForAttempt(20)).isEqualTo(Duration.ofMinutes(2));
    }

    private static KnowledgeOcrProperties ocr(String provider, String endpoint, String key,
                                               List<String> hosts) {
        return new KnowledgeOcrProperties(provider, endpoint, key, hosts, 20,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 65_536L);
    }

    private static KnowledgeIngestionProperties ingestion(Duration lease, Duration initialBackoff) {
        return new KnowledgeIngestionProperties(false, Duration.ofSeconds(2), 2, lease,
                Duration.ofSeconds(1), 1, 3, initialBackoff, Duration.ofMinutes(2),
                2_000, 104_857_600L, 200, 20);
    }
}
