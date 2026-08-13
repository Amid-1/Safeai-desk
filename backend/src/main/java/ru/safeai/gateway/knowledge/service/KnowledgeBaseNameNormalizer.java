package ru.safeai.gateway.knowledge.service;

import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Objects;

public final class KnowledgeBaseNameNormalizer {

    private static final int MAX_LENGTH = 255;

    private KnowledgeBaseNameNormalizer() {
    }

    public static String normalize(String value) {
        Objects.requireNonNull(value, "value не должен быть null");

        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);

            if (Character.isISOControl(codePoint)) {
                throw new BadRequestException(
                        "Название базы знаний содержит управляющие символы"
                );
            }

            offset += Character.charCount(codePoint);
        }

        String normalized = value
                .strip()
                .replaceAll("\\s+", " ");

        if (normalized.isEmpty()) {
            throw new BadRequestException(
                    "Название базы знаний не должно быть пустым"
            );
        }

        if (normalized.length() > MAX_LENGTH) {
            throw new BadRequestException(
                    "Название базы знаний не должно превышать "
                            + MAX_LENGTH
                            + " символов"
            );
        }

        return normalized;
    }
}
