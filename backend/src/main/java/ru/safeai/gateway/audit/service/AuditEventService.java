package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventService {

    private final AuditCommandFactory commandFactory;
    private final AuditOutboxWriter outboxWriter;

    public void record(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(
                AuditActor.fromPrincipal(currentUser),
                targetOrganizationId,
                eventType,
                details
        );
    }

    public void record(
            SafeAiUserPrincipal currentUser,
            String actorDisplayName,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(
                AuditActor.fromPrincipal(
                        currentUser,
                        actorDisplayName
                ),
                targetOrganizationId,
                eventType,
                details
        );
    }

    public void record(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        record(
                AuditActor.fromPrincipal(currentUser),
                targetOrganizationId,
                eventType,
                details
        );
    }

    public void record(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        record(
                actor,
                targetOrganizationId,
                eventType,
                details == null ? Map.of() : details.toMap()
        );
    }

    public void record(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        AuditCommand command = commandFactory.create(
                actor,
                targetOrganizationId,
                eventType,
                details
        );

        /*
         * Присоединяется к transaction вызывающего business service.
         * Ошибка durable audit intent должна откатить security mutation.
         */
        outboxWriter.writeRequired(command);
    }

    public void recordSystem(
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(
                AuditActor.system(),
                targetOrganizationId,
                eventType,
                details
        );
    }
}
