package ru.safeai.gateway.audit.listener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.ratelimit.RateLimitExceededEvent;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RateLimitAuditListenerTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    @Mock
    private AuditEventService auditEventService;

    private RateLimitAuditListener listener;

    @BeforeEach
    void setUp() {
        listener = new RateLimitAuditListener(
                auditEventService
        );
    }

    @Test
    void unauthenticatedLoginEventUsesSystemAudit() {
        RateLimitExceededEvent event =
                new RateLimitExceededEvent(
                        null,
                        null,
                        "admin@test.com",
                        null,
                        PLATFORM_ORGANIZATION_ID,
                        "LOGIN_IP",
                        100,
                        "10m",
                        Map.of(
                                "dimension",
                                "IP",
                                "ipCount",
                                101
                        )
                );

        listener.onRateLimitExceeded(event);

        verify(auditEventService).recordSystem(
                eq(PLATFORM_ORGANIZATION_ID),
                eq(AuditEventType.RATE_LIMIT_EXCEEDED),
                argThat(
                        (Map<String, Object> details) ->
                                "LOGIN_IP".equals(
                                        details.get("type")
                                )
                                        && Integer.valueOf(100)
                                        .equals(
                                                details.get("limit")
                                        )
                                        && "10m".equals(
                                                details.get("window")
                                        )
                                        && "IP".equals(
                                                details.get("dimension")
                                        )
                                        && Integer.valueOf(101)
                                        .equals(
                                                details.get("ipCount")
                                        )
                )
        );

        verify(auditEventService, never()).record(
                any(AuditActor.class),
                any(UUID.class),
                any(AuditEventType.class),
                org.mockito.ArgumentMatchers
                        .<Map<String, Object>>any()
        );
    }

    @Test
void authenticatedAiEventKeepsActorSnapshot() {
    RateLimitExceededEvent event =
            new RateLimitExceededEvent(
                    USER_ID,
                    ORGANIZATION_ID,
                    "user@test.com",
                    "Test User",
                    ORGANIZATION_ID,
                    "AI_MESSAGE_USER",
                    20,
                    "1h",
                    Map.of(
                            "dimension",
                            "USER",
                            "userCount",
                            20
                    )
            );

    listener.onRateLimitExceeded(event);

    ArgumentCaptor<AuditActor> actorCaptor =
            ArgumentCaptor.forClass(
                    AuditActor.class
            );

    verify(auditEventService).record(
            actorCaptor.capture(),
            eq(ORGANIZATION_ID),
            eq(AuditEventType.RATE_LIMIT_EXCEEDED),
            argThat(
                    (Map<String, Object> details) ->
                            "AI_MESSAGE_USER".equals(
                                    details.get("type")
                            )
                                    && Integer.valueOf(20).equals(
                                    details.get("limit")
                            )
                                    && "1h".equals(
                                    details.get("window")
                            )
                                    && "USER".equals(
                                    details.get("dimension")
                            )
                                    && Integer.valueOf(20).equals(
                                    details.get("userCount")
                            )
            )
    );

    AuditActor actor =
            actorCaptor.getValue();

    assertThat(actor.userId())
            .isEqualTo(USER_ID);

    assertThat(actor.organizationId())
            .isEqualTo(ORGANIZATION_ID);

    assertThat(actor.email())
            .isEqualTo("user@test.com");

    assertThat(actor.displayName())
            .isEqualTo("Test User");

    verify(auditEventService, never())
            .recordSystem(
                    any(UUID.class),
                    any(AuditEventType.class),
                    anyMap()
            );
}

    @Test
    void bothEventDoesNotInsertNullLimitIntoImmutableMap() {
        RateLimitExceededEvent event =
                new RateLimitExceededEvent(
                        USER_ID,
                        ORGANIZATION_ID,
                        "user@test.com",
                        null,
                        ORGANIZATION_ID,
                        "AI_MESSAGE_USER_AND_ORGANIZATION",
                        null,
                        "1h",
                        Map.of(
                                "dimension",
                                "BOTH",
                                "userLimit",
                                20,
                                "organizationLimit",
                                1_000
                        )
                );

        listener.onRateLimitExceeded(event);

        verify(auditEventService).record(
                any(AuditActor.class),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.RATE_LIMIT_EXCEEDED),
                argThat(
                        (Map<String, Object> details) ->
                                !details.containsKey("limit")
                                        && "BOTH".equals(
                                                details.get("dimension")
                                        )
                                        && "AI_MESSAGE_USER_AND_ORGANIZATION"
                                        .equals(
                                                details.get("type")
                                        )
                                        && Integer.valueOf(20)
                                        .equals(
                                                details.get("userLimit")
                                        )
                                        && Integer.valueOf(1_000)
                                        .equals(
                                                details.get(
                                                        "organizationLimit"
                                                )
                                        )
                                        && "1h".equals(
                                                details.get("window")
                                        )
                )
        );
    }
}