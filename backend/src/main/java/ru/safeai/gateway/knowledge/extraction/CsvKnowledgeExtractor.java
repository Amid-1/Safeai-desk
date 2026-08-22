package ru.safeai.gateway.knowledge.extraction;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.util.ArrayList;
import java.util.List;

@Component
public class CsvKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION = "csv-rfc4180-utf8-v1";

    private final KnowledgeIngestionProperties properties;

    public CsvKnowledgeExtractor(KnowledgeIngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return "text/csv".equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        String source = ExtractionTextSupport.decodeUtf8(content, "CSV-файл");
        List<List<String>> records = parse(source);
        String text = render(records);
        ExtractionTextSupport.addAndCheck(
                0,
                text,
                properties.maxExtractedChars()
        );
        return new ExtractedDocument(
                VERSION,
                List.of(new ExtractedSection(null, "CSV", text)),
                text.length()
        );
    }

    private static List<List<String>> parse(String source) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean quoteClosed = false;

        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < source.length()
                            && source.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                        quoteClosed = true;
                    }
                } else {
                    field.append(character);
                }
                continue;
            }

            if (quoteClosed && character != ','
                    && character != '\r' && character != '\n') {
                throw invalidCsv();
            }
            if (character == '"') {
                if (!field.isEmpty()) {
                    throw invalidCsv();
                }
                quoted = true;
            } else if (character == ',') {
                record.add(normalizeField(field));
                field.setLength(0);
                quoteClosed = false;
            } else if (character == '\r' || character == '\n') {
                record.add(normalizeField(field));
                addRecord(records, record);
                record = new ArrayList<>();
                field.setLength(0);
                quoteClosed = false;
                if (character == '\r' && index + 1 < source.length()
                        && source.charAt(index + 1) == '\n') {
                    index++;
                }
            } else {
                field.append(character);
            }
        }

        if (quoted) {
            throw invalidCsv();
        }
        if (!field.isEmpty() || !record.isEmpty() || quoteClosed) {
            record.add(normalizeField(field));
            addRecord(records, record);
        }
        return records;
    }

    private static void addRecord(
            List<List<String>> records,
            List<String> record
    ) {
        if (record.stream().anyMatch(value -> !value.isBlank())) {
            records.add(List.copyOf(record));
        }
    }

    private static String normalizeField(StringBuilder field) {
        return ExtractionTextSupport.normalize(field.toString())
                .replace('\n', ' ');
    }

    private static String render(List<List<String>> records) {
        if (records.isEmpty()) {
            return "";
        }

        List<String> header = records.getFirst();
        StringBuilder output = new StringBuilder();
        appendRow(output, header, null);
        for (int index = 1; index < records.size(); index++) {
            appendRow(output, records.get(index), header);
        }
        return ExtractionTextSupport.normalize(output.toString());
    }

    private static void appendRow(
            StringBuilder output,
            List<String> row,
            List<String> header
    ) {
        if (!output.isEmpty()) {
            output.append('\n');
        }
        for (int column = 0; column < row.size(); column++) {
            if (column > 0) {
                output.append(" | ");
            }
            if (header != null) {
                String name = column < header.size()
                        && !header.get(column).isBlank()
                        ? header.get(column)
                        : "column_" + (column + 1);
                output.append(name).append(": ");
            }
            output.append(row.get(column));
        }
    }

    private static KnowledgeIngestionException invalidCsv() {
        return new KnowledgeIngestionException(
                "INVALID_CSV",
                "CSV содержит некорректное quoted-поле",
                false
        );
    }
}
