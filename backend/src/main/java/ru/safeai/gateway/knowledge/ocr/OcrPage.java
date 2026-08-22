package ru.safeai.gateway.knowledge.ocr;

import java.util.Objects;

public record OcrPage(
        int pageNumber,
        String text
) {
    public OcrPage {
        if (pageNumber < 1) {
            throw new IllegalArgumentException(
                    "OCR pageNumber должен быть >= 1"
            );
        }

        Objects.requireNonNull(
                text,
                "OCR text не должен быть null"
        );
    }
}
