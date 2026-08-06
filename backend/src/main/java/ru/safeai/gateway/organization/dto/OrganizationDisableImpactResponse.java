package ru.safeai.gateway.organization.dto;

import java.util.UUID;

public record OrganizationDisableImpactResponse(
        UUID organizationId,
        long organizationVersion,
        long enabledUsers,
        long administrators,
        long activeRefreshSessions,
        long activeChatOperations
) {
}
