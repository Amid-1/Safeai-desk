package ru.safeai.gateway.knowledge.extraction;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
@Timeout(5)
class KnowledgeExtractionServiceRegressionTest {
    @Test
    void unsupportedMediaTypeFailsBeforeSubmittingWorkerTask() {
        var service = service(List.of(), Duration.ofMillis(200));
        try {
            assertThatThrownBy(() -> service.extract("application/x-untrusted", new byte[] {1}))
                    .isInstanceOf(KnowledgeIngestionException.class)
                    .extracting("code").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void extractorExceptionsAreWrappedWithoutCopyingInternalDetailsIntoPublicMessage() {
        var service = service(List.of(extractor(() -> {
            throw new IllegalStateException("confidential parser internal state");
        })), Duration.ofMillis(500));
        try {
            assertThatThrownBy(() -> service.extract("text/plain", new byte[]{1}))
                    .isInstanceOf(KnowledgeIngestionException.class)
                    .satisfies(error -> {
                        var failure = (KnowledgeIngestionException) error;
                        assertThat(failure.code()).isEqualTo("EXTRACTION_FAILED");
                        assertThat(failure.retryable()).isFalse();
                        assertThat(failure.getMessage()).doesNotContain("confidential parser internal state");
                    });
        } finally {
            service.shutdown();
        }
    }

    @Test
    void boundedTimeoutFailsRetryablyAndCancelsWorker() {
        var service = service(List.of(extractor(() -> {
            try {
                Thread.sleep(30_000);
                return new ExtractedDocument("test", List.of(), 0);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("worker interrupted", exception);
            }
        })), Duration.ofMillis(75));
        try {
            assertThatThrownBy(() -> service.extract("text/plain", new byte[]{1}))
                    .isInstanceOf(KnowledgeIngestionException.class)
                    .satisfies(error -> {
                        var failure = (KnowledgeIngestionException) error;
                        assertThat(failure.code()).isEqualTo("EXTRACTION_TIMEOUT");
                        assertThat(failure.retryable()).isTrue();
                    });
        } finally {
            service.shutdown();
        }
    }

    private static KnowledgeExtractionService service(List<KnowledgeDocumentExtractor> extractors,
                                                      Duration timeout) {
        var properties = new KnowledgeIngestionProperties(false, Duration.ofSeconds(2), 1,
                Duration.ofSeconds(5), timeout, 1, 3, Duration.ofSeconds(1),
                Duration.ofSeconds(5), 2_000, 104_857_600L, 200, 30);
        return new KnowledgeExtractionService(extractors, properties);
    }

    private static KnowledgeDocumentExtractor extractor(java.util.concurrent.Callable<ExtractedDocument> callback) {
        return new KnowledgeDocumentExtractor() {
            @Override public boolean supports(String mediaType) { return "text/plain".equals(mediaType); }
            @Override public ExtractedDocument extract(byte[] bytes) {
                try { return callback.call(); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }
        };
    }
}
