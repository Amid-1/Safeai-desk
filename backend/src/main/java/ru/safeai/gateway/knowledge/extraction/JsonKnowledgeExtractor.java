package ru.safeai.gateway.knowledge.extraction;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Component
public class JsonKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION = "jackson-3.1.4-json-v1";
    private static final JsonMapper JSON_MAPPER =
            JsonMapper.builder().build();

    private final KnowledgeIngestionProperties properties;

    public JsonKnowledgeExtractor(KnowledgeIngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return "application/json".equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        String source = ExtractionTextSupport.decodeUtf8(content, "JSON-файл");
        final JsonNode root;
        try {
            root = JSON_MAPPER.readTree(source);
        } catch (Exception exception) {
            throw new KnowledgeIngestionException(
                    "INVALID_JSON",
                    "Не удалось разобрать JSON",
                    false,
                    exception
            );
        }

        if (root == null) {
            throw new KnowledgeIngestionException(
                    "INVALID_JSON",
                    "JSON не содержит корневого значения",
                    false
            );
        }

        String text = ExtractionTextSupport.normalize(root.toPrettyString());
        ExtractionTextSupport.addAndCheck(
                0,
                text,
                properties.maxExtractedChars()
        );
        return new ExtractedDocument(
                VERSION,
                List.of(new ExtractedSection(null, "JSON", text)),
                text.length()
        );
    }
}
