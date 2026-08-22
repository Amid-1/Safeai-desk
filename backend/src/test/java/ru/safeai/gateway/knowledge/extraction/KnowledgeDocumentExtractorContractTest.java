package ru.safeai.gateway.knowledge.extraction;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.config.KnowledgeOcrProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;
import ru.safeai.gateway.knowledge.ocr.DisabledKnowledgeOcrProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentExtractorContractTest {

    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet";
    private static final String PPTX =
            "application/vnd.openxmlformats-officedocument"
                    + ".presentationml.presentation";

    private final KnowledgeIngestionProperties properties = properties(20_000);
    private final KnowledgeOcrProperties ocrProperties =
            new KnowledgeOcrProperties(
                    "disabled", null, null, List.of(), 20,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 65_536L
            );
    private final List<KnowledgeDocumentExtractor> extractors = List.of(
            new PdfKnowledgeExtractor(
                    properties,
                    ocrProperties,
                    new DisabledKnowledgeOcrProvider()
            ),
            new DocxKnowledgeExtractor(properties),
            new PlainTextKnowledgeExtractor(properties),
            new HtmlKnowledgeExtractor(properties),
            new MarkdownKnowledgeExtractor(properties),
            new CsvKnowledgeExtractor(properties),
            new XlsxKnowledgeExtractor(properties),
            new PptxKnowledgeExtractor(properties),
            new JsonKnowledgeExtractor(properties),
            new XmlKnowledgeExtractor(properties)
    );

    @Test
    void everyAcceptedMediaTypeHasExactlyOneExtractor() {
        Set<String> acceptedMediaTypes = Set.of(
                "application/pdf",
                DOCX,
                "text/plain",
                "text/html",
                "text/markdown",
                "text/csv",
                XLSX,
                PPTX,
                "application/json",
                "application/xml"
        );

        assertThat(acceptedMediaTypes)
                .allSatisfy(mediaType -> assertThat(extractors.stream()
                        .filter(extractor -> extractor.supports(mediaType))
                        .count())
                        .as("extractor count for %s", mediaType)
                        .isEqualTo(1));
    }

    @Test
    void extractsMarkdownCsvJsonAndXmlAsSearchableText() {
        ExtractedDocument markdown = extractor("text/markdown").extract(
                utf8("# SafeAI\n\nGoverned knowledge platform.")
        );
        ExtractedDocument csv = extractor("text/csv").extract(
                utf8("id,description\n1,\"SafeAI, \"\"Gateway\"\"\"\n")
        );
        ExtractedDocument json = extractor("application/json").extract(
                utf8("""
                        {"service":"safeai","enabled":true}
                        """)
        );
        ExtractedDocument xml = extractor("application/xml").extract(
                utf8("""
                        <service environment="prod">
                          <name>SafeAI</name>
                          <enabled>true</enabled>
                        </service>
                        """)
        );

        assertThat(markdown.sections().getFirst().heading())
                .isEqualTo("SafeAI");
        assertThat(markdown.sections().getFirst().text())
                .contains("Governed knowledge platform");
        assertThat(csv.sections().getFirst().text())
                .contains("id: 1", "description: SafeAI, \"Gateway\"");
        assertThat(json.sections().getFirst().text())
                .contains("\"service\" : \"safeai\"", "\"enabled\" : true");
        assertThat(xml.sections().getFirst().text())
                .contains(
                        "service.@environment: prod",
                        "service.name: SafeAI",
                        "service.enabled: true"
                );
    }

    @Test
    void extractsRealXlsxWithSheetProvenance() throws IOException {
        ExtractedDocument document = extractor(XLSX).extract(xlsx());

        assertThat(document.sections()).hasSize(1);
        assertThat(document.sections().getFirst().heading())
                .isEqualTo("Models");
        assertThat(document.sections().getFirst().text())
                .contains("A1: Provider", "B2: gpt-4.1");
    }

    @Test
    void extractsRealPptxWithSlideProvenance() throws IOException {
        ExtractedDocument document = extractor(PPTX).extract(pptx());

        assertThat(document.sections()).hasSize(1);
        assertThat(document.sections().getFirst().pageNumber()).isEqualTo(1);
        assertThat(document.sections().getFirst().heading())
                .isEqualTo("Слайд 1");
        assertThat(document.sections().getFirst().text())
                .contains("SafeAI Architecture");
    }

    @Test
    void rejectsMalformedStructuredDocuments() {
        assertThatThrownBy(() -> extractor("text/csv").extract(
                utf8("id,name\n1,\"unterminated")
        )).isInstanceOf(KnowledgeIngestionException.class)
                .hasMessageContaining("CSV");

        assertThatThrownBy(() -> extractor("application/xml").extract(
                utf8("""
                        <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                        <root>&xxe;</root>
                        """)
        )).isInstanceOf(KnowledgeIngestionException.class)
                .hasMessageContaining("XML");

        assertThatThrownBy(() -> extractor(XLSX).extract(
                utf8("not an OOXML archive")
        )).isInstanceOf(KnowledgeIngestionException.class)
                .hasMessageContaining("XLSX");
    }

    @Test
    void enforcesExtractedTextLimitForNewFormats() {
        MarkdownKnowledgeExtractor extractor =
                new MarkdownKnowledgeExtractor(properties(1_000));

        assertThatThrownBy(() -> extractor.extract(
                utf8("x".repeat(1_001))
        )).isInstanceOf(KnowledgeIngestionException.class)
                .hasMessageContaining("безопасный лимит");
    }

    private KnowledgeDocumentExtractor extractor(String mediaType) {
        return extractors.stream()
                .filter(candidate -> candidate.supports(mediaType))
                .findFirst()
                .orElseThrow();
    }

    private static byte[] xlsx() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Models");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Provider");
            header.createCell(1).setCellValue("Model");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("OpenAI");
            data.createCell(1).setCellValue("gpt-4.1");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] pptx() throws IOException {
        try (XMLSlideShow presentation = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = presentation.createSlide();
            slide.createTextBox().setText("SafeAI Architecture");
            presentation.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static KnowledgeIngestionProperties properties(
            int maxExtractedChars
    ) {
        return new KnowledgeIngestionProperties(
                false,
                Duration.ofSeconds(2),
                4,
                Duration.ofMinutes(3),
                Duration.ofSeconds(60),
                2,
                5,
                Duration.ofSeconds(10),
                Duration.ofMinutes(10),
                maxExtractedChars,
                104_857_600L,
                1_200,
                150
        );
    }
}
