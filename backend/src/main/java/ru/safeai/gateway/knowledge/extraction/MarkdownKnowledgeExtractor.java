package ru.safeai.gateway.knowledge.extraction;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;

import java.util.List;

@Component
public class MarkdownKnowledgeExtractor
        implements KnowledgeDocumentExtractor {

    private static final String VERSION = "markdown-utf8-v1";

    private final KnowledgeIngestionProperties properties;

    public MarkdownKnowledgeExtractor(
            KnowledgeIngestionProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String mediaType) {
        return "text/markdown".equalsIgnoreCase(mediaType);
    }

    @Override
    public ExtractedDocument extract(byte[] content) {
        String text = ExtractionTextSupport.normalize(
                ExtractionTextSupport.decodeUtf8(content, "Markdown-файл")
        );
        ExtractionTextSupport.addAndCheck(
                0,
                text,
                properties.maxExtractedChars()
        );
        return new ExtractedDocument(
                VERSION,
                List.of(new ExtractedSection(null, firstHeading(text), text)),
                text.length()
        );
    }

    private static String firstHeading(String markdown) {
        boolean fencedCode = false;
        for (String line : markdown.lines().toList()) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```")
                    || stripped.startsWith("~~~")) {
                fencedCode = !fencedCode;
                continue;
            }
            if (fencedCode || !stripped.startsWith("#")) {
                continue;
            }

            int markerEnd = 0;
            while (markerEnd < stripped.length()
                    && markerEnd < 6
                    && stripped.charAt(markerEnd) == '#') {
                markerEnd++;
            }
            if (markerEnd < stripped.length()
                    && Character.isWhitespace(stripped.charAt(markerEnd))) {
                String heading = stripped.substring(markerEnd).strip();
                if (!heading.isEmpty()) {
                    return heading.replaceFirst("\\s+#+\\s*$", "");
                }
            }
        }
        return null;
    }
}
