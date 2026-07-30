package ru.safeai.gateway.audit.service;

import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AuditCommand(
        UUID eventId,
        AuditActor actor,
        UUID targetOrganizationId,
        AuditEventType eventType,
        Map<String, Object> details,
        Instant occurredAt
) {
    public AuditCommand {
        Objects.requireNonNull(eventId, "eventId не должен быть null");
        Objects.requireNonNull(
                targetOrganizationId,
                "targetOrganizationId не должен быть null"
        );
        Objects.requireNonNull(eventType, "eventType не должен быть null");
        Objects.requireNonNull(occurredAt, "occurredAt не должен быть null");

        details = details == null
                ? Map.of()
                : Map.copyOf(details);
    }
}
