package ru.safeai.gateway.audit.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.UUID;

public record AuditEventFilter(
        String eventType,
        String userEmail,
        UUID userId,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dateFrom,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant dateTo,

        UUID organizationId
) {
}