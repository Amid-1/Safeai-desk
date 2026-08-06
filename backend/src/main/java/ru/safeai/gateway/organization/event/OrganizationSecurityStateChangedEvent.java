package ru.safeai.gateway.organization.event;

import java.util.Objects;
import java.util.UUID;

public record OrganizationSecurityStateChangedEvent(
        UUID organizationId,
        long authVersion
) {
    public OrganizationSecurityStateChangedEvent {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        if (authVersion < 0L) {
            throw new IllegalArgumentException(
                    "authVersion не может быть отрицательной"
            );
        }
    }
}
