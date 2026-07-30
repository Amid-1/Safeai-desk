package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BestEffortStandaloneAuditService {

    private final AuditCommandFactory commandFactory;
    private final AuditOutboxWriter outboxWriter;

    public void tryRecord(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            AuditDetails details
    ) {
        tryRecord(
                actor,
                targetOrganizationId,
                eventType,
                details == null
                        ? Map.of()
                        : details.toMap()
        );
    }

    public void tryRecord(
            AuditActor actor,
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        try {
            AuditCommand command =
                    commandFactory.create(
                            actor,
                            targetOrganizationId,
                            eventType,
                            details
                    );

            /*
             * Вызов transaction proxy находится внутри try/catch,
             * поэтому перехватываются ошибки save, flush и commit.
             */
            outboxWriter.writeStandalone(command);
        } catch (RuntimeException exception) {
            log.error(
                    "Unable to persist standalone audit intent: "
                            + "eventType={}, actorUserId={}, "
                            + "actorOrganizationId={}, "
                            + "targetOrganizationId={}",
                    eventType,
                    actor == null
                            ? null
                            : actor.userId(),
                    actor == null
                            ? null
                            : actor.organizationId(),
                    targetOrganizationId,
                    exception
            );
        }
    }

    public void tryRecordSystem(
            UUID targetOrganizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        tryRecord(
                AuditActor.system(),
                targetOrganizationId,
                eventType,
                details
        );
    }
}