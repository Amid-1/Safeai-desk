package ru.safeai.gateway.knowledge.extraction;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;

import java.util.List;

@Component
public class PlainTextKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION =
            "plain-text-utf8-v2";

    private final KnowledgeIngestionProperties properties;

    public PlainTextKnowledgeExtractor(
            KnowledgeIngestionProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public boolean supports(
            String mediaType
    ) {
        return "text/plain".equalsIgnoreCase(
                mediaType
        );
    }

    @Override
    public ExtractedDocument extract(
            byte[] content
    ) {
        String text =
                ExtractionTextSupport.normalize(
                        ExtractionTextSupport.decodeUtf8(
                                content,
                                "TXT-файл"
                        )
                );

        ExtractionTextSupport.addAndCheck(
                0,
                text,
                properties.maxExtractedChars()
        );

        return new ExtractedDocument(
                VERSION,
                List.of(
                        new ExtractedSection(
                                null,
                                null,
                                text
                        )
                ),
                text.length()
        );
    }
}
