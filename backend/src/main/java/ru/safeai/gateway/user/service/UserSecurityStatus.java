package ru.safeai.gateway.user.service;

import java.util.Objects;
import java.util.UUID;

public record UserSecurityStatus(
        UUID organizationId,
        boolean userEnabled,
        boolean organizationEnabled,
        long tokenVersion,
        long organizationAuthVersion
) {

    public UserSecurityStatus {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        if (tokenVersion < 0) {
            throw new IllegalArgumentException(
                    "tokenVersion не может быть отрицательным"
            );
        }

        if (organizationAuthVersion < 0) {
            throw new IllegalArgumentException(
                    "organizationAuthVersion не может быть отрицательным"
            );
        }
    }
}
