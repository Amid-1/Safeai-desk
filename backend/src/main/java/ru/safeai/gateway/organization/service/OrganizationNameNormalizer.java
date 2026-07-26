package ru.safeai.gateway.organization.service;

import ru.safeai.gateway.common.exception.BadRequestException;

import java.util.Locale;

public final class OrganizationNameNormalizer {

    private OrganizationNameNormalizer() {
    }

    public static String canonicalize(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(
                    "Название организации не должно быть пустым"
            );
        }

        String canonical = value
                .strip()
                .replaceAll("[\\p{Z}\\s]+", " ");

        if (canonical.isBlank()) {
            throw new BadRequestException(
                    "Название организации не должно быть пустым"
            );
        }

        if (canonical.length() > 255) {
            throw new BadRequestException(
                    "Название организации не должно превышать 255 символов"
            );
        }

        return canonical;
    }

    public static String normalize(String canonicalName) {
        return canonicalize(canonicalName).toLowerCase(Locale.ROOT);
    }
}
