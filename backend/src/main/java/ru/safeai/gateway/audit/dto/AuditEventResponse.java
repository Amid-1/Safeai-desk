package ru.safeai.gateway.audit.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        UUID userId,
        String userEmail,
        String eventType,
        Map<String, Object> details,
        LocalDateTime createdAt
) {
}