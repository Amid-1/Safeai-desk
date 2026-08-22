package ru.safeai.gateway.knowledge.extraction;

public record ExtractedSection(
        Integer pageNumber,
        String heading,
        String text
) {
}
