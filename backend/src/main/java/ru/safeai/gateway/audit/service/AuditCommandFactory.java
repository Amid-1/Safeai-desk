package ru.safeai.gateway.audit.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public final class AuditCommandFactory {

    private final AuditDetailsSanitizer sanitizer;
    private final AuditDatabaseClock databaseClock;

    public AuditCommandFactory(
            AuditDetailsSanitizer sanitizer,
            AuditDatabaseClock databaseClock
    ) {
        this.sanitizer =
                Objects.requireNonNull(
                        sanitizer,
                        "sanitizer не должен быть null"
                );

        this.databaseClock =
                Objects.requireNonNull(
                        databaseClock,
                        "databaseClock не должен быть null"
                );
    }

    /**
     * Legacy/unstructured details.
     *
     * <p>Текстовые значения принимаются только для полей,
     * разрешённых центральным metadata allowlist.</p>
     */
    public AuditCommand create(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        return command(
                actor,
                targetOrganizationId,
                eventType,
                sanitizer.sanitize(
                        details
                )
        );
    }

    /**
     * Preferred path.
     *
     * <p>Payload shape задаётся отдельным AuditDetails type,
     * после чего дополнительно применяется defense-in-depth
     * sanitization.</p>
     */
    public AuditCommand createTyped(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        Map<String, Object> rawDetails =
                details == null
                        ? Map.of()
                        : details.toMap();

        return command(
                actor,
                targetOrganizationId,
                eventType,
                sanitizer.sanitizeTyped(
                        rawDetails
                )
        );
    }

    private AuditCommand command(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        Objects.requireNonNull(
                targetOrganizationId,
                "targetOrganizationId не должен быть null"
        );

        Objects.requireNonNull(
                eventType,
                "eventType не должен быть null"
        );

        Objects.requireNonNull(
                details,
                "details не должен быть null"
        );

        return new AuditCommand(
                UUID.randomUUID(),
                actor,
                targetOrganizationId,
                eventType,
                details,
                databaseClock.now()
        );
    }
}