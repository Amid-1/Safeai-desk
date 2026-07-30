package ru.safeai.gateway.audit.listener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.BestEffortStandaloneAuditService;
import ru.safeai.gateway.ratelimit.RateLimitExceededEvent;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RateLimitAuditListenerTest {

    @Mock
    private BestEffortStandaloneAuditService
            bestEffortAuditService;

    @Test
    void listenerPreservesDetailsAndImmutableActorSnapshot() {
        UUID actorUserId = UUID.randomUUID();

        UUID actorOrganizationId =
                UUID.randomUUID();

        UUID targetOrganizationId =
                UUID.randomUUID();

        RateLimitExceededEvent event =
                new RateLimitExceededEvent(
                        actorUserId,
                        actorOrganizationId,
                        "ADMIN@Test.Com",
                        "Admin",
                        targetOrganizationId,
                        "AI_MESSAGE_USER",
                        100,
                        "1h",
                        Map.of(
                                "count",
                                101L,
                                "decision",
                                "FIRST_EXCEEDED"
                        )
                );

        RateLimitAuditListener listener =
                new RateLimitAuditListener(
                        bestEffortAuditService
                );

        listener.onRateLimitExceeded(event);

        ArgumentCaptor<AuditActor> actorCaptor =
                ArgumentCaptor.forClass(
                        AuditActor.class
                );

        ArgumentCaptor<Map<String, Object>>
                detailsCaptor =
                ArgumentCaptor.captor();

        verify(bestEffortAuditService).tryRecord(
                actorCaptor.capture(),
                eq(targetOrganizationId),
                eq(
                        AuditEventType
                                .RATE_LIMIT_EXCEEDED
                ),
                detailsCaptor.capture()
        );

        AuditActor actor =
                actorCaptor.getValue();

        assertThat(actor.userId())
                .isEqualTo(actorUserId);

        assertThat(actor.organizationId())
                .isEqualTo(actorOrganizationId);

        assertThat(actor.email())
                .isEqualTo("admin@test.com");

        assertThat(actor.displayName())
                .isEqualTo("Admin");

        assertThat(detailsCaptor.getValue())
                .containsEntry(
                        "type",
                        "AI_MESSAGE_USER"
                )
                .containsEntry(
                        "limit",
                        100
                )
                .containsEntry(
                        "window",
                        "1h"
                )
                .containsEntry(
                        "count",
                        101L
                )
                .containsEntry(
                        "decision",
                        "FIRST_EXCEEDED"
                );
    }
}