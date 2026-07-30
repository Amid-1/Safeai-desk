package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionSystemException;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BestEffortStandaloneAuditServiceTest {

    @Mock
    private AuditCommandFactory commandFactory;

    @Mock
    private AuditOutboxWriter outboxWriter;

    @Test
    void commitFailureIsContainedOutsideTransactionalWriter() {
        BestEffortStandaloneAuditService service =
                service();

        AuditCommand command = command();

        when(commandFactory.create(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(command);

        doThrow(
                new TransactionSystemException(
                        "commit failed"
                )
        ).when(outboxWriter)
                .writeStandalone(command);

        assertThatCode(() ->
                service.tryRecord(
                        command.actor(),
                        command.targetOrganizationId(),
                        command.eventType(),
                        command.details()
                )
        ).doesNotThrowAnyException();

        verify(outboxWriter)
                .writeStandalone(command);
    }

    @Test
    void successfulStandaloneWriteCompletesNormally() {
        BestEffortStandaloneAuditService service =
                service();

        AuditCommand command = command();

        when(commandFactory.create(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(command);

        assertThatCode(() ->
                service.tryRecord(
                        command.actor(),
                        command.targetOrganizationId(),
                        command.eventType(),
                        command.details()
                )
        ).doesNotThrowAnyException();

        verify(outboxWriter)
                .writeStandalone(command);
    }

    private BestEffortStandaloneAuditService service() {
        return new BestEffortStandaloneAuditService(
                commandFactory,
                outboxWriter
        );
    }

    private AuditCommand command() {
        UUID actorOrganizationId =
                UUID.randomUUID();

        return new AuditCommand(
                UUID.randomUUID(),
                new AuditActor(
                        UUID.randomUUID(),
                        actorOrganizationId,
                        "admin@test.com",
                        "Admin"
                ),
                UUID.randomUUID(),
                AuditEventType.RATE_LIMIT_EXCEEDED,
                Map.of("limit", 100),
                Instant.parse(
                        "2026-07-30T08:00:00Z"
                )
        );
    }
}
