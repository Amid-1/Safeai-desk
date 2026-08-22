package ru.safeai.gateway.knowledge.extraction;

import java.util.List;

public record ExtractedDocument(
        String extractorVersion,
        List<ExtractedSection> sections,
        int characterCount
) {

    public ExtractedDocument {
        sections = List.copyOf(sections);
    }
}
