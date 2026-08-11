package ru.safeai.gateway.audit.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,

        /*
         * userId/userEmail сохранены как compatibility aliases
         * immutable actor snapshot-а.
         */
        UUID userId,
        UUID actorOrganizationId,

        /*
         * organizationId — target organization события.
         * targetOrganizationName — immutable name snapshot на момент события.
         */
        UUID organizationId,
        String targetOrganizationName,

        String userEmail,
        String userDisplayName,

        String eventType,
        Map<String, Object> details,
        Instant createdAt
) {

    public AuditEventResponse {
        details = details == null || details.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<>(details)
                );
    }
}