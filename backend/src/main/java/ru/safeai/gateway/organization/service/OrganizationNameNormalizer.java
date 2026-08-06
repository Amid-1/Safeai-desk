package ru.safeai.gateway.organization.service;

import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Locale;

public final class OrganizationNameNormalizer {

    private static final int MAX_NAME_LENGTH = 255;

    private OrganizationNameNormalizer() {
    }

    public static String canonicalize(
            String value
    ) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(
                    "Название организации не должно быть пустым"
            );
        }

        String canonical = value
                .strip()
                .replaceAll(
                        "[\\p{Z}\\s]+",
                        " "
                );

        if (canonical.isBlank()) {
            throw new BadRequestException(
                    "Название организации не должно быть пустым"
            );
        }

        if (canonical.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException(
                    "Название организации не должно превышать "
                            + MAX_NAME_LENGTH
                            + " символов"
            );
        }

        return canonical;
    }

    public static String normalize(
            String value
    ) {
        return canonicalize(value)
                .toLowerCase(Locale.ROOT);
    }
}
