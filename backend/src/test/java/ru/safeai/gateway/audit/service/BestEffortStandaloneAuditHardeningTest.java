package ru.safeai.gateway.audit.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BestEffortStandaloneAuditHardeningTest {

    @Mock private AuditCommandFactory commandFactory;
    @Mock private AuditOutboxWriter outboxWriter;

    @Test
    void typedAuditUsesTypedSanitizerAndRequiredStandaloneWriter() {
        AuditActor actor = AuditActor.system();
        UUID organizationId = UUID.randomUUID();
        AuditDetails typed = () -> Map.of("usageStatus", "AVAILABLE");
        AuditCommand command = new AuditCommand(
                UUID.randomUUID(), actor, organizationId,
                AuditEventType.AI_RESPONSE_RECEIVED,
                Map.of("usageStatus", "AVAILABLE"), Instant.parse("2026-10-17T00:00:00Z"));

        when(commandFactory.createTyped(actor, organizationId,
                AuditEventType.AI_RESPONSE_RECEIVED, typed)).thenReturn(command);

        BestEffortStandaloneAuditService service =
                new BestEffortStandaloneAuditService(commandFactory, outboxWriter);
        service.tryRecord(actor, organizationId,
                AuditEventType.AI_RESPONSE_RECEIVED, typed);

        verify(commandFactory).createTyped(actor, organizationId,
                AuditEventType.AI_RESPONSE_RECEIVED, typed);
        verify(outboxWriter).writeStandaloneRequired(command);
    }

    @Test
    void failedStandaloneAuditNeverLogsThrowableMessageOrThrowableProxy() {
        String secret = "Bearer extremely-sensitive-token-and-prompt";
        AuditActor actor = AuditActor.system();
        UUID organizationId = UUID.randomUUID();
        AuditCommand command = new AuditCommand(
                UUID.randomUUID(), actor, organizationId,
                AuditEventType.RATE_LIMIT_EXCEEDED,
                Map.of("limit", 10), Instant.parse("2026-10-17T00:00:00Z"));

        when(commandFactory.create(any(), any(), any(), any()))
                .thenReturn(command);
        doThrow(new IllegalStateException(secret))
                .when(outboxWriter).writeStandaloneRequired(command);

        Logger logger = (Logger) LoggerFactory.getLogger(
                BestEffortStandaloneAuditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BestEffortStandaloneAuditService service =
                    new BestEffortStandaloneAuditService(commandFactory, outboxWriter);
            assertThatCode(() -> service.tryRecord(actor, organizationId,
                    AuditEventType.RATE_LIMIT_EXCEEDED, Map.of("limit", 10)))
                    .doesNotThrowAnyException();

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getFormattedMessage())
                    .contains("java.lang.IllegalStateException")
                    .doesNotContain(secret);
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
