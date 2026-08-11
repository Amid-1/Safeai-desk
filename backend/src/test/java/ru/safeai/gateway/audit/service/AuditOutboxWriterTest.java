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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private AuditTargetOrganizationSnapshotProvider snapshotProvider;

    @Test
    void writeRequired_shouldCaptureTargetOrganizationSnapshot() {
        UUID eventId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        UUID actorOrganizationId = UUID.randomUUID();
        UUID targetOrganizationId = UUID.randomUUID();

        Instant occurredAt =
                Instant.parse("2026-08-11T18:00:00Z");
        Instant enqueuedAt =
                Instant.parse("2026-08-11T18:00:01Z");

        Clock clock = Clock.fixed(
                enqueuedAt,
                ZoneOffset.UTC
        );

        when(snapshotProvider.findName(targetOrganizationId))
                .thenReturn(Optional.of("Demo Company"));

        AuditOutboxWriter writer = new AuditOutboxWriter(
                repository,
                snapshotProvider,
                clock
        );

        AuditCommand command = new AuditCommand(
                eventId,
                new AuditActor(
                        actorUserId,
                        actorOrganizationId,
                        "admin@test.com",
                        "Demo Admin"
                ),
                targetOrganizationId,
                AuditEventType.USER_UPDATED,
                Map.of("changed", true),
                occurredAt
        );

        writer.writeRequired(command);

        ArgumentCaptor<AuditOutboxEntity> captor =
                ArgumentCaptor.forClass(
                        AuditOutboxEntity.class
                );

        verify(repository).save(captor.capture());

        AuditOutboxEntity saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo(eventId);
        assertThat(saved.getTargetOrganizationId())
                .isEqualTo(targetOrganizationId);
        assertThat(saved.getTargetOrganizationName())
                .isEqualTo("Demo Company");
        assertThat(saved.getOccurredAt())
                .isEqualTo(occurredAt);
        assertThat(saved.getCreatedAt())
                .isEqualTo(enqueuedAt);
        assertThat(saved.getNextAttemptAt())
                .isEqualTo(enqueuedAt);
        assertThat(saved.getAttemptCount()).isZero();

    }
}
