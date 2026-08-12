package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    private static final UUID USER_ID =
            UUID.randomUUID();

    private static final UUID ACTOR_ORGANIZATION_ID =
            UUID.randomUUID();

    private static final UUID TARGET_ORGANIZATION_ID =
            UUID.randomUUID();

    @Mock
    private AuditCommandFactory commandFactory;

    @Mock
    private AuditOutboxWriter outboxWriter;

    @Test
    void recordFromPrincipalBuildsImmutableActorSnapshot() {
        AuditEventService service =
                service();

        SafeAiUserPrincipal principal =
                principal();

        AuditCommand command =
                command();

        when(
                commandFactory.create(
                        any(AuditActor.class),
                        eq(TARGET_ORGANIZATION_ID),
                        eq(
                                AuditEventType
                                        .USER_UPDATED
                        ),
                        anyMap()
                )
        ).thenReturn(
                command
        );

        service.record(
                principal,
                "Admin Name",
                TARGET_ORGANIZATION_ID,
                AuditEventType.USER_UPDATED,
                Map.of(
                        "targetUserId",
                        UUID.randomUUID()
                )
        );

        ArgumentCaptor<AuditActor> actorCaptor =
                ArgumentCaptor.forClass(
                        AuditActor.class
                );

        verify(
                commandFactory
        ).create(
                actorCaptor.capture(),
                eq(TARGET_ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .USER_UPDATED
                ),
                anyMap()
        );

        AuditActor actor =
                actorCaptor.getValue();

        assertThat(
                actor.userId()
        ).isEqualTo(
                USER_ID
        );

        assertThat(
                actor.organizationId()
        ).isEqualTo(
                ACTOR_ORGANIZATION_ID
        );

        assertThat(
                actor.email()
        ).isEqualTo(
                "admin@test.com"
        );

        assertThat(
                actor.displayName()
        ).isEqualTo(
                "Admin Name"
        );

        verify(
                outboxWriter
        ).writeRequired(
                command
        );
    }

    @Test
    void serializationFailureDoesNotCreatePartialOutboxIntent() {
        AuditEventService service =
                service();

        doThrow(
                new IllegalArgumentException(
                        "Sanitized audit details "
                                + "не сериализуется в JSON"
                )
        ).when(
                commandFactory
        ).create(
                any(AuditActor.class),
                eq(TARGET_ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .USER_UPDATED
                ),
                anyMap()
        );

        assertThatThrownBy(() ->
                service.record(
                        principal(),
                        TARGET_ORGANIZATION_ID,
                        AuditEventType.USER_UPDATED,
                        Map.of(
                                "safe",
                                "value"
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "не сериализуется в JSON"
                );

        verifyNoInteractions(
                outboxWriter
        );
    }

    @Test
    void requiredWriterFailureIsNotSwallowed() {
        AuditEventService service =
                service();

        AuditCommand command =
                command();

        when(
                commandFactory.create(
                        any(AuditActor.class),
                        eq(TARGET_ORGANIZATION_ID),
                        eq(
                                AuditEventType
                                        .USER_UPDATED
                        ),
                        anyMap()
                )
        ).thenReturn(
                command
        );

        doThrow(
                new IllegalStateException(
                        "audit database unavailable"
                )
        ).when(
                outboxWriter
        ).writeRequired(
                command
        );

        assertThatThrownBy(() ->
                service.record(
                        principal(),
                        TARGET_ORGANIZATION_ID,
                        AuditEventType.USER_UPDATED,
                        Map.of()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "audit database unavailable"
                );

        verify(
                outboxWriter
        ).writeRequired(
                command
        );
    }

    private AuditEventService service() {
        return new AuditEventService(
                commandFactory,
                outboxWriter
        );
    }

    private AuditCommand command() {
        return new AuditCommand(
                UUID.randomUUID(),
                new AuditActor(
                        USER_ID,
                        ACTOR_ORGANIZATION_ID,
                        "admin@test.com",
                        "Admin"
                ),
                TARGET_ORGANIZATION_ID,
                AuditEventType.USER_UPDATED,
                Map.of(),
                Instant.parse(
                        "2026-07-30T08:00:00Z"
                )
        );
    }

    private SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        USER_ID,
                        ACTOR_ORGANIZATION_ID,
                        "admin@test.com",
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );
    }
}