package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditCommandFactory {

    private final AuditDetailsSanitizer sanitizer;
    private final Clock clock;

    public AuditCommand create(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        return new AuditCommand(
                UUID.randomUUID(),
                actor,
                targetOrganizationId,
                eventType,
                sanitizer.sanitize(details),
                clock.instant()
        );
    }
}
