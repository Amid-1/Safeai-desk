package ru.safeai.gateway.knowledge.extraction;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class PlainTextKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION = "plain-text-utf8-v1";

    private final KnowledgeIngestionProperties properties;

    public PlainTextKnowledgeExtractor(
            KnowledgeIngestionProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return "text/plain".equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
                text = text.substring(1);
            }
            text = ExtractionTextSupport.normalize(text);
            ExtractionTextSupport.addAndCheck(
                    0,
                    text,
                    properties.maxExtractedChars()
            );
            return new ExtractedDocument(
                    VERSION,
                    List.of(new ExtractedSection(null, null, text)),
                    text.length()
            );
        } catch (CharacterCodingException exception) {
            throw new KnowledgeIngestionException(
                    "INVALID_UTF8",
                    "TXT-файл должен быть корректным UTF-8",
                    false,
                    exception
            );
        }
    }
}
