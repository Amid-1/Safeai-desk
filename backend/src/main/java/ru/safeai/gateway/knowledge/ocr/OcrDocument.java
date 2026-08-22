package ru.safeai.gateway.knowledge.ocr;

import java.util.List;
import java.util.Objects;

public record OcrDocument(
        String modelVersion,
        List<OcrPage> pages
) {
    public OcrDocument {
        if (modelVersion == null
                || modelVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "OCR modelVersion is required"
            );
        }

        Objects.requireNonNull(
                pages,
                "OCR pages не должен быть null"
        );

        pages = List.copyOf(
                pages
        );
    }
}
