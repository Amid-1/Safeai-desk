package ru.safeai.gateway.organization.event;

import java.util.UUID;

public record OrganizationSecurityStateChangedEvent(
        UUID organizationId,
        long authVersion
) {
}
