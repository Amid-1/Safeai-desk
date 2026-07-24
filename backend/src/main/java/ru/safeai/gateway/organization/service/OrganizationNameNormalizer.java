package ru.safeai.gateway.organization.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class OrganizationNameNormalizer {

    private static final int MAX_NAME_LENGTH = 255;

    private static final Pattern WHITESPACE =
            Pattern.compile(
                    "\\s+",
                    Pattern.UNICODE_CHARACTER_CLASS
            );

    private OrganizationNameNormalizer() {
    }

    public static String canonicalName(
            String value
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Название организации не должно быть null"
            );
        }

        String canonical = WHITESPACE
                .matcher(value.trim())
                .replaceAll(" ");

        if (canonical.isBlank()) {
            throw new IllegalArgumentException(
                    "Название организации не должно быть пустым"
            );
        }

        if (canonical.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Название организации не должно превышать "
                            + MAX_NAME_LENGTH
                            + " символов"
            );
        }

        return canonical;
    }

    public static String normalizedName(
            String value
    ) {
        return canonicalName(value)
                .toLowerCase(Locale.ROOT);
    }
}