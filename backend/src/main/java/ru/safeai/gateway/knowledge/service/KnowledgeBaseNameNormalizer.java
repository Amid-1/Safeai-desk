package ru.safeai.gateway.knowledge.service;

import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Objects;

public final class KnowledgeBaseNameNormalizer {

    private static final int MAX_LENGTH = 255;

    private KnowledgeBaseNameNormalizer() {
    }

    public static String normalize(String value) {
        Objects.requireNonNull(
                value,
                "value не должен быть null"
        );

        validateNoControlCharacters(value);

        String normalized =
                normalizeWhitespace(value);

        validateNotBlank(normalized);
        validateLength(normalized);

        return normalized;
    }

    private static void validateNoControlCharacters(
            String value
    ) {
        value.codePoints()
                .filter(Character::isISOControl)
                .findFirst()
                .ifPresent(codePoint -> {
                    throw new BadRequestException(
                            "Название базы знаний содержит управляющие символы"
                    );
                });
    }

    private static String normalizeWhitespace(
            String value
    ) {
        String stripped = value.strip();
        StringBuilder result =
                new StringBuilder(stripped.length());

        boolean previousWasWhitespace = false;

        for (
                int offset = 0;
                offset < stripped.length();
        ) {
            int codePoint =
                    stripped.codePointAt(offset);

            boolean whitespace =
                    Character.isWhitespace(codePoint)
                            || Character.isSpaceChar(codePoint);

            if (whitespace) {
                if (
                        !previousWasWhitespace
                                && !result.isEmpty()
                ) {
                    result.append(' ');
                }

                previousWasWhitespace = true;
            } else {
                result.appendCodePoint(codePoint);
                previousWasWhitespace = false;
            }

            offset += Character.charCount(
                    codePoint
            );
        }

        int length = result.length();

        if (
                length > 0
                        && result.charAt(length - 1) == ' '
        ) {
            result.setLength(length - 1);
        }

        return result.toString();
    }

    private static void validateNotBlank(
            String value
    ) {
        if (value.isEmpty()) {
            throw new BadRequestException(
                    "Название базы знаний не должно быть пустым"
            );
        }
    }

    private static void validateLength(
            String value
    ) {
        int characterCount =
                value.codePointCount(
                        0,
                        value.length()
                );

        if (characterCount > MAX_LENGTH) {
            throw new BadRequestException(
                    "Название базы знаний не должно превышать "
                            + MAX_LENGTH
                            + " символов"
            );
        }
    }
}