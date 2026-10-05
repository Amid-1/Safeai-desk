package ru.safeai.gateway.knowledge.ocr;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.config.KnowledgeOcrProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class KnowledgeOcrBoundaryRegressionTest {

    private static final long MAX_RESPONSE_BYTES =
            65_536L;

    private static final long MAX_INPUT_BYTES =
            25L * 1024L * 1024L;

    @Test
    void providerMustRejectEmptyPdfBeforeNetwork() {
        HttpKnowledgeOcrProvider provider =
                httpProvider();

        assertThatThrownBy(
                () -> provider.extractPdf(
                        null
                )
        )
                .isInstanceOf(
                        KnowledgeIngestionException.class
                )
                .extracting(
                        "code"
                )
                .isEqualTo(
                        "OCR_INVALID_INPUT"
                );

        assertThatThrownBy(
                () -> provider.extractPdf(
                        new byte[0]
                )
        )
                .isInstanceOf(
                        KnowledgeIngestionException.class
                )
                .extracting(
                        "code"
                )
                .isEqualTo(
                        "OCR_INVALID_INPUT"
                );
    }

    @Test
    void configuredOcrBoundaryRetainsExplicitInputAndResponseLimits() {
        KnowledgeOcrProperties properties =
                configuredProperties();

        assertThat(
                properties.maxResponseBytes()
        ).isEqualTo(
                MAX_RESPONSE_BYTES
        );

        assertThat(
                properties.maxInputBytes()
        ).isEqualTo(
                MAX_INPUT_BYTES
        );
    }

    @Test
    void disabledProviderDoesNotClaimOcrCapability() {
        DisabledKnowledgeOcrProvider provider =
                new DisabledKnowledgeOcrProvider();

        assertThat(
                provider.enabled()
        ).isFalse();
    }

    @Test
    void documentAndPagesDefensivelyCopyAndRequirePositivePageNumbers() {
        ArrayList<OcrPage> pages =
                new ArrayList<>(
                        List.of(
                                new OcrPage(
                                        1,
                                        "Evidence"
                                )
                        )
                );

        OcrDocument document =
                new OcrDocument(
                        "model-v1",
                        pages
                );

        pages.clear();

        assertThat(
                document.pages()
        ).hasSize(
                1
        );

        assertThatThrownBy(
                () -> document.pages()
                        .clear()
        ).isInstanceOf(
                UnsupportedOperationException.class
        );

        assertThatThrownBy(
                () -> new OcrPage(
                        0,
                        "value"
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );

        assertThatThrownBy(
                () -> new OcrPage(
                        1,
                        null
                )
        ).isInstanceOf(
                NullPointerException.class
        );

        assertThatThrownBy(
                () -> new OcrDocument(
                        " ",
                        List.of()
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }

    private static HttpKnowledgeOcrProvider httpProvider() {
        return new HttpKnowledgeOcrProvider(
                configuredProperties()
        );
    }

    private static KnowledgeOcrProperties configuredProperties() {
        return new KnowledgeOcrProperties(
                "http",
                "https://ocr.safeai.test/api",
                "secret",
                List.of(
                        "ocr.safeai.test"
                ),
                20,
                Duration.ofSeconds(
                        1
                ),
                Duration.ofSeconds(
                        2
                ),
                MAX_RESPONSE_BYTES,
                MAX_INPUT_BYTES
        );
    }
}
