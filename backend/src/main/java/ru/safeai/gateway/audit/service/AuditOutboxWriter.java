package ru.safeai.gateway.audit.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;
import ru.safeai.gateway.audit.spi.AuditTargetOrganizationSnapshotProvider;

import java.time.Instant;
import java.util.Objects;

/**
 * Transaction boundary for durable audit intents.
 *
 * <p>Required business audit joins the caller's transaction.
 * Standalone audit deliberately starts an independent transaction.</p>
 */
@Service
public class AuditOutboxWriter {

    private final AuditOutboxRepository repository;
    private final AuditTargetOrganizationSnapshotProvider targetOrganizationSnapshotProvider;
    private final AuditDatabaseClock databaseClock;

    public AuditOutboxWriter(
            AuditOutboxRepository repository,
            AuditTargetOrganizationSnapshotProvider targetOrganizationSnapshotProvider,
            AuditDatabaseClock databaseClock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository не должен быть null");
        this.targetOrganizationSnapshotProvider = Objects.requireNonNull(
                targetOrganizationSnapshotProvider,
                "targetOrganizationSnapshotProvider не должен быть null"
        );
        this.databaseClock = Objects.requireNonNull(databaseClock, "databaseClock не должен быть null");
    }

    /**
     * Requires an active business transaction and flushes the durable audit intent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeRequiredInCurrentTransaction(AuditCommand command) {
        persist(command);
    }

    /**
     * Uses an independent transaction when called through this Spring bean's proxy.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeStandaloneRequired(AuditCommand command) {
        persist(command);
    }

    /**
     * Flush is not a commit of the caller's transaction.
     */
    private void persist(AuditCommand command) {
        repository.saveAndFlush(toEntity(command));
    }

    private AuditOutboxEntity toEntity(AuditCommand command) {
        Objects.requireNonNull(command, "command не должен быть null");

        AuditOutboxEntity entity = new AuditOutboxEntity();
        entity.setId(command.eventId());

        AuditActor actor = command.actor();
        if (actor != null) {
            entity.setActorUserId(actor.userId());
            entity.setActorOrganizationId(actor.organizationId());
            entity.setActorEmail(actor.email());
            entity.setActorDisplayName(actor.displayName());
        }

        entity.setTargetOrganizationId(command.targetOrganizationId());
        entity.setTargetOrganizationName(
                targetOrganizationSnapshotProvider
                        .findName(command.targetOrganizationId())
                        .orElse(null)
        );
        entity.setEventType(command.eventType().name());
        entity.setDetails(command.details());
        entity.setOccurredAt(command.occurredAt());

        Instant now = databaseClock.now();
        entity.setCreatedAt(now);
        entity.setAttemptCount(0);
        entity.setNextAttemptAt(now);
        return entity;
    }
}
