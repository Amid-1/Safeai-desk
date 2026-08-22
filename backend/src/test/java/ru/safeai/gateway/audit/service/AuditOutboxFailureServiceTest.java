package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.config.AuditOutboxProperties;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditOutboxFailureServiceTest {

    private static final Instant NOW =
            Instant.parse(
                    "2026-07-30T08:00:00Z"
            );

    @Mock
    private AuditOutboxRepository repository;

    @Test
    void firstFailureSchedulesInitialBackoff() {
        AuditOutboxEntity entity =
                entity(0);

        when(
                repository.findByIdForUpdate(
                        entity.getId()
                )
        ).thenReturn(
                Optional.of(entity)
        );

        AuditOutboxFailureService.FailureResult result =
                service(10).markFailure(
                        entity.getId(),
                        new IllegalStateException(
                                "failure"
                        )
                );

        assertThat(result.rowFound()).isTrue();
        assertThat(result.deadLettered()).isFalse();
        assertThat(result.attemptCount()).isEqualTo(1);

        assertThat(entity.getAttemptCount())
                .isEqualTo(1);

        assertThat(entity.getNextAttemptAt())
                .isEqualTo(
                        NOW.plusSeconds(2)
                );

        assertThat(entity.getDeadLetteredAt())
                .isNull();

        assertThat(entity.getLastError())
                .isEqualTo(
                        IllegalStateException.class
                                .getName()
                );

        verify(repository).save(entity);
    }

    @Test
    void lastAllowedFailureMovesRowToDeadLetter() {
        AuditOutboxEntity entity =
                entity(2);

        when(
                repository.findByIdForUpdate(
                        entity.getId()
                )
        ).thenReturn(
                Optional.of(entity)
        );

        AuditOutboxFailureService.FailureResult result =
                service(3).markFailure(
                        entity.getId(),
                        new IllegalArgumentException(
                                "failure"
                        )
                );

        assertThat(result.deadLettered()).isTrue();
        assertThat(result.attemptCount()).isEqualTo(3);

        assertThat(entity.getNextAttemptAt())
                .isNull();

        assertThat(entity.getDeadLetteredAt())
                .isEqualTo(NOW);

        assertThat(entity.getLastError())
                .isEqualTo(
                        IllegalArgumentException.class
                                .getName()
                );

        verify(repository).save(entity);
    }

    @Test
    void sqlFailureStoresOnlySafeDiagnosticMetadata() {
        AuditOutboxEntity entity =
                entity(0);

        when(
                repository.findByIdForUpdate(
                        entity.getId()
                )
        ).thenReturn(
                Optional.of(entity)
        );

        SQLException sqlException =
                new SQLException(
                        "SQL contained secret-token-value",
                        "23514",
                        99
                );

        service(10).markFailure(
                entity.getId(),
                new IllegalStateException(
                        "wrapper",
                        sqlException
                )
        );

        assertThat(entity.getLastError())
                .contains("sqlState=23514")
                .contains("errorCode=99")
                .doesNotContain(
                        "secret-token-value"
                );
    }

    @Test
    void missingRowHandlesUncertainCommit() {
        UUID id =
                UUID.randomUUID();

        when(
                repository.findByIdForUpdate(id)
        ).thenReturn(
                Optional.empty()
        );

        AuditOutboxFailureService.FailureResult result =
                service(10).markFailure(
                        id,
                        new IllegalStateException()
                );

        assertThat(result.rowFound()).isFalse();
        assertThat(result.outboxId()).isEqualTo(id);
    }

    private AuditOutboxFailureService service(
            int maxAttempts
    ) {
        return new AuditOutboxFailureService(
                repository,
                new AuditOutboxProperties(
                        100,
                        maxAttempts,
                        Duration.ofSeconds(2),
                        Duration.ofHours(1),
                        1_000
                ),
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                )
        );
    }

    private AuditOutboxEntity entity(
            int attemptCount
    ) {
        AuditOutboxEntity entity =
                new AuditOutboxEntity();

        entity.setId(
                UUID.randomUUID()
        );
        entity.setOccurredAt(
                NOW
        );
        entity.setCreatedAt(
                NOW
        );
        entity.setAttemptCount(
                attemptCount
        );
        entity.setNextAttemptAt(
                NOW
        );

        return entity;
    }
}
