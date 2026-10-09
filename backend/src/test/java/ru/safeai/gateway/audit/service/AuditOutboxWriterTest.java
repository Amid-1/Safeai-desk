package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;
import ru.safeai.gateway.audit.spi.AuditTargetOrganizationSnapshotProvider;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditOutboxWriterTest {

    @Mock
    private AuditOutboxRepository repository;

    @Mock
    private AuditTargetOrganizationSnapshotProvider
            snapshotProvider;

    @Mock
    private AuditDatabaseClock databaseClock;

    @Test
    void writeRequiredInCurrentTransaction_shouldCaptureTargetOrganizationSnapshot() {
        UUID eventId =
                UUID.randomUUID();

        UUID actorUserId =
                UUID.randomUUID();

        UUID actorOrganizationId =
                UUID.randomUUID();

        UUID targetOrganizationId =
                UUID.randomUUID();

        Instant occurredAt =
                Instant.parse(
                        "2026-08-11T18:00:00Z"
                );

        Instant enqueuedAt =
                Instant.parse(
                        "2026-08-11T18:00:01Z"
                );

        when(
                snapshotProvider.findName(
                        targetOrganizationId
                )
        ).thenReturn(
                Optional.of(
                        "Demo Company"
                )
        );

        when(
                databaseClock.now()
        ).thenReturn(
                enqueuedAt
        );

        AuditOutboxWriter writer =
                new AuditOutboxWriter(
                        repository,
                        snapshotProvider,
                        databaseClock
                );

        AuditActor actor =
                new AuditActor(
                        actorUserId,
                        actorOrganizationId,
                        "admin@test.com",
                        "Demo Admin"
                );

        Map<String, Object> details =
                Map.of(
                        "changed",
                        true
                );

        AuditCommand command =
                new AuditCommand(
                        eventId,
                        actor,
                        targetOrganizationId,
                        AuditEventType.USER_UPDATED,
                        details,
                        occurredAt
                );

        writer.writeRequiredInCurrentTransaction(
                command
        );

        ArgumentCaptor<AuditOutboxEntity> captor =
                ArgumentCaptor.forClass(
                        AuditOutboxEntity.class
                );

        verify(
                repository
        ).saveAndFlush(
                captor.capture()
        );

        verify(
                snapshotProvider
        ).findName(
                targetOrganizationId
        );

        verify(
                databaseClock
        ).now();

        AuditOutboxEntity saved =
                captor.getValue();

        assertThat(
                saved.getId()
        ).isEqualTo(
                eventId
        );

        assertThat(
                saved.getActorUserId()
        ).isEqualTo(
                actorUserId
        );

        assertThat(
                saved.getActorOrganizationId()
        ).isEqualTo(
                actorOrganizationId
        );

        assertThat(
                saved.getActorEmail()
        ).isEqualTo(
                "admin@test.com"
        );

        assertThat(
                saved.getActorDisplayName()
        ).isEqualTo(
                "Demo Admin"
        );

        assertThat(
                saved.getTargetOrganizationId()
        ).isEqualTo(
                targetOrganizationId
        );

        assertThat(
                saved.getTargetOrganizationName()
        ).isEqualTo(
                "Demo Company"
        );

        assertThat(
                saved.getEventType()
        ).isEqualTo(
                AuditEventType.USER_UPDATED.name()
        );

        assertThat(
                saved.getDetails()
        ).isEqualTo(
                details
        );

        assertThat(
                saved.getOccurredAt()
        ).isEqualTo(
                occurredAt
        );

        assertThat(
                saved.getCreatedAt()
        ).isEqualTo(
                enqueuedAt
        );

        assertThat(
                saved.getNextAttemptAt()
        ).isEqualTo(
                enqueuedAt
        );

        assertThat(
                saved.getAttemptCount()
        ).isZero();
    }
}