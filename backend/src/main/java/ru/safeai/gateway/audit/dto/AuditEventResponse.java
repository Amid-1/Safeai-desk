package ru.safeai.gateway.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,

        /*
         * userId/userEmail оставлены для совместимости API.
         * Это immutable actor snapshot.
         */
        UUID userId,
        UUID actorOrganizationId,
        UUID organizationId,
        String userEmail,
        String userDisplayName,

        String eventType,
        Map<String, Object> details,
        Instant createdAt
) {
}
