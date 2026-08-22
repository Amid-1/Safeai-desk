package ru.safeai.gateway.knowledge.extraction;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

@Component
public class DocxKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".wordprocessingml.document";
    private static final String VERSION = "poi-5.5.1-docx-v1";

    private final KnowledgeIngestionProperties properties;

    public DocxKnowledgeExtractor(KnowledgeIngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return MEDIA_TYPE.equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        validateArchive(content);
        try (XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(content)
        )) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                appendLine(text, paragraph.getText());
                ensureTextLimit(text.length());
            }
            for (XWPFTable table : document.getTables()) {
                appendTable(text, table);
                ensureTextLimit(text.length());
            }
            String normalized = ExtractionTextSupport.normalize(
                    text.toString()
            );
            return new ExtractedDocument(
                    VERSION,
                    List.of(new ExtractedSection(null, null, normalized)),
                    normalized.length()
            );
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof KnowledgeIngestionException ingestion) {
                throw ingestion;
            }
            throw new KnowledgeIngestionException(
                    "INVALID_DOCX",
                    "Не удалось безопасно извлечь текст из DOCX",
                    false,
                    exception
            );
        }
    }

    private void validateArchive(byte[] content) {
        OoxmlPackageSupport.validate(
                content,
                "word/document.xml",
                properties.maxDocxUncompressedBytes(),
                "DOCX"
        );
    }

    private void appendTable(StringBuilder target, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            boolean first = true;
            for (XWPFTableCell cell : row.getTableCells()) {
                if (!first) {
                    target.append(" | ");
                }
                target.append(cell.getText());
                first = false;
            }
            target.append('\n');
        }
    }

    private static void appendLine(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) {
            target.append(value.strip()).append('\n');
        }
    }

    private void ensureTextLimit(int length) {
        if (length > properties.maxExtractedChars()) {
            throw ExtractionTextSupport.tooLarge();
        }
    }

}
