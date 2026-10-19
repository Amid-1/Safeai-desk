package ru.safeai.gateway.knowledge.service;

import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Locale;

/**
 * Validates and escapes an untrusted, literal, case-insensitive name search.
 * SQL wildcard characters in user input must never expand the result set.
 */
final class KnowledgeBaseSearchPattern {

    private static final int MAX_CODE_POINTS = 100;

    private KnowledgeBaseSearchPattern() {
    }

    /** Returns null for an empty filter, otherwise a LIKE pattern. */
    static String from(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        String normalized = query.strip();
        int points = 0;

        for (int i = 0; i < normalized.length(); ) {
            int value = normalized.codePointAt(i);
            if ((value >= 0xD800 && value <= 0xDFFF)
                    || Character.isISOControl(value)) {
                throw new BadRequestException(
                        "Поиск содержит недопустимый символ"
                );
            }
            i += Character.charCount(value);
            if (++points > MAX_CODE_POINTS) {
                throw new BadRequestException(
                        "Поиск не должен превышать 100 символов"
                );
            }
        }

        if (normalized.isEmpty()) {
            return null;
        }

        String safe = normalized.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");

        return "%" + safe + "%";
    }
}
