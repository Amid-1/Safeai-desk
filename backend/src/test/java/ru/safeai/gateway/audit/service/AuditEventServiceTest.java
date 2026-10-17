// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/audit/service/AuditEventServiceTest.java
package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.details.AuditDetails;
import ru.safeai.gateway.audit.model.AuditActor;
import ru.safeai.gateway.audit.spi.AuditActorSnapshotProvider;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the mandatory audit command pipeline.
 *
 * <p>The typed entry point MUST use AuditCommandFactory#createTyped,
 * whereas the legacy map entry point MUST retain its original create route.
 * Neither command-construction nor outbox-write failures may be swallowed.</p>
 *
 * <p>These are mock-based contract tests. Database transaction/flush/rollback
 * behavior is covered by separate PostgreSQL integration tests.</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACTOR_ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID TARGET_ORGANIZATION_ID = UUID.randomUUID();
    private static final String ACTOR_EMAIL = "admin@test.com";

    @Mock
    private AuditCommandFactory commandFactory;

    @Mock
    private AuditOutboxWriter outboxWriter;

    @Mock
    private AuditActorSnapshotProvider actorSnapshotProvider;

    @Test
    void requiredRecordFromPrincipalBuildsImmutableActorSnapshot() {
        AuditEventService service = service();
        SafeAiUserPrincipal principal = principal();
        UUID targetUserId = UUID.randomUUID();
        Map<String, Object> expectedDetails = Map.of("targetUserId", targetUserId);
        AuditDetails details = () -> expectedDetails;

        AuditActor expectedActor = stubActorUserWithName(principal, "Admin Name");
        AuditCommand command = command(expectedActor);

        when(commandFactory.createTyped(
                any(AuditActor.class),
                eq(TARGET_ORGANIZATION_ID),
                eq(AuditEventType.USER_UPDATED),
                same(details)
        )).thenReturn(command);

        service.recordPrincipalSnapshot(
                principal,
                TARGET_ORGANIZATION_ID,
                AuditEventType.USER_UPDATED,
                details
        );

        ArgumentCaptor<AuditActor> actorCaptor =
                ArgumentCaptor.forClass(AuditActor.class);
        ArgumentCaptor<AuditDetails> detailsCaptor =
                ArgumentCaptor.forClass(AuditDetails.class);

        verify(actorSnapshotProvider).resolve(same(principal), isNull());
        verify(commandFactory).createTyped(
                actorCaptor.capture(),
                eq(TARGET_ORGANIZATION_ID),
                eq(AuditEventType.USER_UPDATED),
                detailsCaptor.capture()
        );

        AuditActor actor = actorCaptor.getValue();
        assertThat(actor).isSameAs(expectedActor);
        assertThat(actor.userId()).isEqualTo(USER_ID);
        assertThat(actor.organizationId()).isEqualTo(ACTOR_ORGANIZATION_ID);
        assertThat(actor.email()).isEqualTo(ACTOR_EMAIL);
        assertThat(actor.displayName()).isEqualTo("Admin Name");

        assertThat(command.actor()).isSameAs(expectedActor);
        assertThat(detailsCaptor.getValue()).isSameAs(details);
        assertThat(detailsCaptor.getValue().toMap())
                .containsExactlyInAnyOrderEntriesOf(expectedDetails);

        verify(outboxWriter).writeRequiredInCurrentTransaction(same(command));
        verifyNoMoreInteractions(commandFactory, outboxWriter, actorSnapshotProvider);
    }

    @Test
    void typedCommandFactoryFailureDoesNotCreatePartialOutboxIntent() {
        AuditEventService service = service();
        SafeAiUserPrincipal principal = principal();
        AuditDetails details = () -> Map.of("safe", "value");
        stubActorUserWithName(principal, "Admin");

        IllegalArgumentException failure = new IllegalArgumentException(
                "Sanitized audit details не сериализуется в JSON"
        );

        when(commandFactory.createTyped(
                any(AuditActor.class),
                eq(TARGET_ORGANIZATION_ID),
                eq(AuditEventType.USER_UPDATED),
                same(details)
        )).thenThrow(failure);

        assertThatThrownBy(() -> service.recordPrincipalSnapshot(
                principal,
                TARGET_ORGANIZATION_ID,
                AuditEventType.USER_UPDATED,
                details
        )).isSameAs(failure);

        verify(actorSnapshotProvider).resolve(same(principal), isNull());
        verify(commandFactory).createTyped(
                any(AuditActor.class),
                eq(TARGET_ORGANIZATION_ID),
                eq(AuditEventType.USER_UPDATED),
                same(details)
        );
        verifyNoInteractions(outboxWriter);
        verifyNoMoreInteractions(commandFactory, actorSnapshotProvider);
    }

    @Test
    void requiredWriterFailureIsNotSwallowed() {
        AuditEventService service = service();
        SafeAiUserPrincipal principal = principal();
        AuditDetails details = () -> Map.of();
        AuditCommand command = command();
        stubActorUserWithName(principal, "Admin");

        when(commandFactory.createTyped(
                any(AuditActor.class),
                eq(TARGET_ORGANIZATION_ID),
                eq(AuditEventType.USER_UPDATED),
                same(details)
        )).thenReturn(command);

        IllegalStateException failure =
                new IllegalStateException("audit database unavailable");
        doThrow(failure)
                .when(outboxWriter)
                .writeRequiredInCurrentTransaction(same(command));

        assertThatThrownBy(() -> service.recordPrincipalSnapshot(
                principal,
                TARGET_ORGANIZATION_ID,
                AuditEventType.USER_UPDATED,
                details
        )).isSameAs(failure);

        verify(actorSnapshotProvider).resolve(same(principal), isNull());
        verify(commandFactory).createTyped(
                any(AuditActor.class),
                eq(TARGET_ORGANIZATION_ID),
                eq(AuditEventType.USER_UPDATED),
                same(details)
        );
        verify(outboxWriter).writeRequiredInCurrentTransaction(same(command));
        verifyNoMoreInteractions(commandFactory, outboxWriter, actorSnapshotProvider);
    }

    @Test
    void actorSnapshotFailureStopsMandatoryAuditBeforeBuildingCommand() {
        AuditEventService service = service();
        SafeAiUserPrincipal principal = principal();
        AuditDetails details = () -> Map.of("safe", "value");
        IllegalStateException failure =
                new IllegalStateException("actor snapshot unavailable");

        when(actorSnapshotProvider.resolve(same(principal), isNull()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.recordPrincipalSnapshot(
                principal,
                TARGET_ORGANIZATION_ID,
                AuditEventType.USER_UPDATED,
                details
        )).isSameAs(failure);

        verify(actorSnapshotProvider).resolve(same(principal), isNull());
        verifyNoInteractions(commandFactory, outboxWriter);
        verifyNoMoreInteractions(actorSnapshotProvider);
    }

    @Test
    @SuppressWarnings("removal")
    void legacyMapEntryPointKeepsRequiredAuditContract() {
        AuditEventService service = service();
        SafeAiUserPrincipal principal = principal();
        Map<String, Object> details = Map.of("targetUserId", UUID.randomUUID());
        AuditCommand command = command();
        stubActorUserWithName(principal, "Admin");

        when(commandFactory.create(
                any(AuditActor.class),
                eq(TARGET_ORGANIZATION_ID),
                eq(AuditEventType.USER_UPDATED),
                same(details)
        )).thenReturn(command);

        service.record(
                principal,
                TARGET_ORGANIZATION_ID,
                AuditEventType.USER_UPDATED,
                details
        );

        verify(actorSnapshotProvider).resolve(same(principal), isNull());
        verify(commandFactory).create(
                any(AuditActor.class),
                eq(TARGET_ORGANIZATION_ID),
                eq(AuditEventType.USER_UPDATED),
                same(details)
        );
        verify(outboxWriter).writeRequiredInCurrentTransaction(same(command));
        verifyNoMoreInteractions(commandFactory, outboxWriter, actorSnapshotProvider);
    }

    private AuditEventService service() {
        return new AuditEventService(
                commandFactory,
                outboxWriter,
                actorSnapshotProvider
        );
    }

    private AuditCommand command() {
        return command(new AuditActor(
                USER_ID,
                ACTOR_ORGANIZATION_ID,
                ACTOR_EMAIL,
                "Admin"
        ));
    }

    private AuditCommand command(AuditActor actor) {
        return new AuditCommand(
                UUID.randomUUID(),
                actor,
                TARGET_ORGANIZATION_ID,
                AuditEventType.USER_UPDATED,
                Map.of(),
                Instant.parse("2026-07-30T08:00:00Z")
        );
    }

    private AuditActor stubActorUserWithName(
            SafeAiUserPrincipal principal,
            String fullName
    ) {
        AuditActor actor = new AuditActor(
                USER_ID,
                ACTOR_ORGANIZATION_ID,
                ACTOR_EMAIL,
                fullName
        );

        when(actorSnapshotProvider.resolve(same(principal), isNull()))
                .thenReturn(actor);
        return actor;
    }

    private SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ACTOR_ORGANIZATION_ID,
                0L,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}
