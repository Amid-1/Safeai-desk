package ru.safeai.gateway.audit.dto;

import org.springframework.format.annotation.DateTimeFormat;
import ru.safeai.gateway.audit.AuditEventType;

import java.time.Instant;
import java.util.UUID;

public record AuditEventFilter(
        AuditEventType eventType,
        String userEmail,
        UUID userId,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dateFrom,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dateTo,

        UUID organizationId
) {
}