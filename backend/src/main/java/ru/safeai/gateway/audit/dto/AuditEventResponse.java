package ru.safeai.gateway.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,

        /*
         * Исторические данные actor snapshot,
         * а не актуальные поля UserEntity.
         */
        UUID userId,
        UUID organizationId,
        String userEmail,
        String userDisplayName,

        String eventType,
        Map<String, Object> details,
        Instant createdAt
) {
}