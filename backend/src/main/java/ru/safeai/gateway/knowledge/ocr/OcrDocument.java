package ru.safeai.gateway.knowledge.ocr;

import java.util.List;

public record OcrDocument(
        String modelVersion,
        List<OcrPage> pages
) {
    public OcrDocument {
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("OCR modelVersion is required");
        }
        pages = List.copyOf(pages);
    }
}
