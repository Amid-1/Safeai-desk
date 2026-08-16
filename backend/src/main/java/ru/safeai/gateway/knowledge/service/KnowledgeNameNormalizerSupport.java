package ru.safeai.gateway.knowledge.service;

import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Objects;

/**
 * Общая нормализация человекочитаемых имён Knowledge-модуля.
 *
 * <p>Нормализатор:
 * <ul>
 *     <li>не допускает {@code null};</li>
 *     <li>отклоняет ISO control characters;</li>
 *     <li>сворачивает последовательности Unicode whitespace/space chars в один пробел;</li>
 *     <li>удаляет пробелы по краям;</li>
 *     <li>проверяет длину по Unicode code points, а не UTF-16 code units.</li>
 * </ul>
 *
 * <p>Класс package-private намеренно: внешний API Knowledge-модуля —
 * специализированные {@link KnowledgeBaseNameNormalizer} и
 * {@link KnowledgeDocumentNameNormalizer}.</p>
 */
final class KnowledgeNameNormalizerSupport {

    private static final int MAX_LENGTH = 255;

    private KnowledgeNameNormalizerSupport() {
    }

    static String normalize(
            String value,
            String fieldDisplayName
    ) {
        Objects.requireNonNull(
                value,
                "value не должен быть null"
        );
        Objects.requireNonNull(
                fieldDisplayName,
                "fieldDisplayName не должен быть null"
        );

        validateNoControlCharacters(
                value,
                fieldDisplayName
        );

        String normalized =
                normalizeWhitespace(value);

        validateNotBlank(
                normalized,
                fieldDisplayName
        );
        validateLength(
                normalized,
                fieldDisplayName
        );

        return normalized;
    }

    private static void validateNoControlCharacters(
            String value,
            String fieldDisplayName
    ) {
        boolean hasControlCharacter =
                value.codePoints()
                        .anyMatch(Character::isISOControl);

        if (hasControlCharacter) {
            throw new BadRequestException(
                    fieldDisplayName
                            + " содержит управляющие символы"
            );
        }
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
                offset += Character.charCount(
                        stripped.codePointAt(offset)
                )
        ) {
            int codePoint =
                    stripped.codePointAt(offset);

            boolean whitespace =
                    Character.isWhitespace(codePoint)
                            || Character.isSpaceChar(codePoint);

            if (whitespace) {
                if (!previousWasWhitespace
                        && !result.isEmpty()) {
                    result.append(' ');
                }

                previousWasWhitespace = true;
                continue;
            }

            result.appendCodePoint(codePoint);
            previousWasWhitespace = false;
        }

        removeTrailingSpace(result);

        return result.toString();
    }

    private static void removeTrailingSpace(
            StringBuilder value
    ) {
        int length = value.length();

        if (length > 0
                && value.charAt(length - 1) == ' ') {
            value.setLength(length - 1);
        }
    }

    private static void validateNotBlank(
            String value,
            String fieldDisplayName
    ) {
        if (value.isEmpty()) {
            throw new BadRequestException(
                    fieldDisplayName
                            + " не должно быть пустым"
            );
        }
    }

    private static void validateLength(
            String value,
            String fieldDisplayName
    ) {
        int characterCount =
                value.codePointCount(
                        0,
                        value.length()
                );

        if (characterCount > MAX_LENGTH) {
            throw new BadRequestException(
                    fieldDisplayName
                            + " не должно превышать "
                            + MAX_LENGTH
                            + " символов"
            );
        }
    }
}