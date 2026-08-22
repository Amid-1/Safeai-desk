package ru.safeai.gateway.knowledge.extraction;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class XlsxKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet";
    private static final String VERSION = "poi-5.5.1-xlsx-v1";

    private final KnowledgeIngestionProperties properties;

    public XlsxKnowledgeExtractor(KnowledgeIngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return MEDIA_TYPE.equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        OoxmlPackageSupport.validate(
                content,
                "xl/workbook.xml",
                properties.maxDocxUncompressedBytes(),
                "XLSX"
        );

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(content)
        )) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT, true);
            formatter.setUseCachedValuesForFormulaCells(true);
            List<ExtractedSection> sections = new ArrayList<>();
            int characterCount = 0;

            for (Sheet sheet : workbook) {
                String text = extractSheet(sheet, formatter);
                if (text.isBlank()) {
                    continue;
                }
                characterCount = ExtractionTextSupport.addAndCheck(
                        characterCount,
                        text,
                        properties.maxExtractedChars()
                );
                sections.add(new ExtractedSection(
                        null,
                        sheet.getSheetName(),
                        text
                ));
            }
            return new ExtractedDocument(VERSION, sections, characterCount);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof KnowledgeIngestionException ingestion) {
                throw ingestion;
            }
            throw new KnowledgeIngestionException(
                    "INVALID_XLSX",
                    "Не удалось безопасно извлечь данные из XLSX",
                    false,
                    exception
            );
        }
    }

    private String extractSheet(Sheet sheet, DataFormatter formatter) {
        StringBuilder output = new StringBuilder();
        for (Row row : sheet) {
            StringBuilder renderedRow = new StringBuilder();
            for (Cell cell : row) {
                String value = ExtractionTextSupport.normalize(
                        formatter.formatCellValue(cell)
                );
                if (value.isBlank()) {
                    continue;
                }
                if (!renderedRow.isEmpty()) {
                    renderedRow.append(" | ");
                }
                renderedRow.append(cell.getAddress().formatAsString())
                        .append(": ")
                        .append(value.replace('\n', ' '));
            }
            if (!renderedRow.isEmpty()) {
                if (!output.isEmpty()) {
                    output.append('\n');
                }
                output.append(renderedRow);
                if (output.length() > properties.maxExtractedChars()) {
                    throw ExtractionTextSupport.tooLarge();
                }
            }
        }
        return output.toString();
    }
}
