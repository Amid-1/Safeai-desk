package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuditOutboxWriter {

    private final AuditOutboxRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRED)
    public void writeRequired(AuditCommand command) {
        repository.save(toEntity(command));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeStandalone(AuditCommand command) {
        repository.save(toEntity(command));
    }

    private AuditOutboxEntity toEntity(
            AuditCommand command
    ) {
        AuditOutboxEntity entity = new AuditOutboxEntity();
        entity.setId(command.eventId());

        AuditActor actor = command.actor();

        if (actor != null) {
            entity.setActorUserId(actor.userId());
            entity.setActorOrganizationId(
                    actor.organizationId()
            );
            entity.setActorEmail(actor.email());
            entity.setActorDisplayName(actor.displayName());
        }

        entity.setTargetOrganizationId(
                command.targetOrganizationId()
        );
        entity.setEventType(command.eventType().name());
        entity.setDetails(command.details());
        entity.setOccurredAt(command.occurredAt());

        Instant now = clock.instant();

        entity.setCreatedAt(now);
        entity.setAttemptCount(0);
        entity.setNextAttemptAt(now);

        return entity;
    }
}
