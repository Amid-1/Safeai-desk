package ru.safeai.gateway.organization.dto;

import java.util.Objects;
import java.util.UUID;

public record OrganizationDisableImpactResponse(
        UUID organizationId,
        long organizationVersion,
        long enabledUsers,
        long administrators,
        long activeRefreshSessions,
        long activeChatOperations
) {
    public OrganizationDisableImpactResponse {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        requireNonNegative(
                organizationVersion,
                "organizationVersion"
        );
        requireNonNegative(
                enabledUsers,
                "enabledUsers"
        );
        requireNonNegative(
                administrators,
                "administrators"
        );
        requireNonNegative(
                activeRefreshSessions,
                "activeRefreshSessions"
        );
        requireNonNegative(
                activeChatOperations,
                "activeChatOperations"
        );
    }

    private static void requireNonNegative(
            long value,
            String field
    ) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    field + " не может быть отрицательным"
            );
        }
    }
}
