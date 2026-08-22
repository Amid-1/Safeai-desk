package ru.safeai.gateway.knowledge.extraction;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class HtmlKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION = "jsoup-1.23.1-v1";

    private final KnowledgeIngestionProperties properties;

    public HtmlKnowledgeExtractor(KnowledgeIngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return "text/html".equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        try {
            Document document = Jsoup.parse(
                    new ByteArrayInputStream(content),
                    StandardCharsets.UTF_8.name(),
                    ""
            );
            document.select("script,style,noscript,template").remove();
            String heading = blankToNull(document.title());
            String text = ExtractionTextSupport.normalize(
                    document.body().wholeText()
            );
            ExtractionTextSupport.addAndCheck(
                    0,
                    text,
                    properties.maxExtractedChars()
            );
            return new ExtractedDocument(
                    VERSION,
                    List.of(new ExtractedSection(null, heading, text)),
                    text.length()
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unexpected in-memory HTML read failure",
                    exception
            );
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
