package ru.safeai.gateway.knowledge.extraction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.config.KnowledgeOcrProperties;
import ru.safeai.gateway.knowledge.ocr.KnowledgeOcrProvider;
import ru.safeai.gateway.knowledge.ocr.OcrDocument;
import ru.safeai.gateway.knowledge.ocr.OcrPage;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfKnowledgeExtractorOcrTest {

    @Test
    void usesOcrForScannedPageAndPreservesPageNumber() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }
        KnowledgeOcrProvider ocr = new KnowledgeOcrProvider() {
            @Override public boolean enabled() { return true; }
            @Override public OcrDocument extractPdf(byte[] content) {
                return new OcrDocument(
                        "test-v1",
                        List.of(new OcrPage(1, "Scanned corporate policy"))
                );
            }
        };
        PdfKnowledgeExtractor extractor = new PdfKnowledgeExtractor(
                ingestionProperties(),
                new KnowledgeOcrProperties(
                        "disabled", null, null, List.of(), 20,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 65_536L
                ),
                ocr
        );

        ExtractedDocument result = extractor.extract(pdf);

        assertThat(result.sections()).singleElement().satisfies(section -> {
            assertThat(section.pageNumber()).isEqualTo(1);
            assertThat(section.text()).contains("Scanned corporate policy");
        });
        assertThat(result.extractorVersion()).contains("test-v1");
    }

    private static KnowledgeIngestionProperties ingestionProperties() {
        return new KnowledgeIngestionProperties(
                true, Duration.ofSeconds(1), 1, Duration.ofMinutes(2),
                Duration.ofSeconds(30), 1, 3, Duration.ofSeconds(1),
                Duration.ofSeconds(5), 100_000, 10_000_000L,
                1_200, 150
        );
    }
}
