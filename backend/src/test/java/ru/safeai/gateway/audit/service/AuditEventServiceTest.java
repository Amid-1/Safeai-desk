package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private UserRepository userRepository;

    private AuditEventService auditEventService;

    @BeforeEach
    void setUp() {
        auditEventService = new AuditEventService(
                auditEventRepository,
                userRepository
        );
    }

    @Test
    void recordSavesAuditWithResolvedActorAndSnapshot() {
        UserEntity actor = actor(
                " Admin@Test.com ",
                "  Test Admin  "
        );

        when(userRepository.findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(actor));

        stubRepositorySave();

        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_SUCCESS,
                Map.of("email", "admin@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity saved = captor.getValue();

        assertThat(saved.getUser()).isSameAs(actor);
        assertThat(saved.getActorUserId()).isEqualTo(USER_ID);
        assertThat(saved.getActorEmail()).isEqualTo("admin@test.com");
        assertThat(saved.getActorDisplayName()).isEqualTo("Test Admin");
        assertThat(saved.getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(saved.getEventType())
                .isEqualTo(AuditEventType.USER_LOGIN_SUCCESS.name());
        assertThat(saved.getDetails())
                .containsEntry("email", "admin@test.com");

        verify(userRepository).findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        );
    }

    @Test
    void recordPreservesActorIdWhenUserDoesNotExist() {
        when(userRepository.findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.empty());

        stubRepositorySave();

        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("email", "missing@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity saved = captor.getValue();

        assertThat(saved.getUser()).isNull();
        assertThat(saved.getActorUserId()).isEqualTo(USER_ID);
        assertThat(saved.getActorEmail()).isNull();
        assertThat(saved.getActorDisplayName()).isNull();

        verify(userRepository).findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        );
    }

    @Test
    void recordSystemDoesNotQueryUserRepository() {
        stubRepositorySave();

        auditEventService.recordSystem(
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("email", "unknown@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity saved = captor.getValue();

        assertThat(saved.getUser()).isNull();
        assertThat(saved.getActorUserId()).isNull();
        assertThat(saved.getActorEmail()).isNull();
        assertThat(saved.getActorDisplayName()).isNull();

        verifyNoInteractions(userRepository);
    }

    @Test
    void blankActorSnapshotValuesAreStoredAsNull() {
        UserEntity actor = actor("   ", "   ");

        when(userRepository.findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(actor));

        stubRepositorySave();

        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_SUCCESS,
                Map.of()
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity saved = captor.getValue();

        assertThat(saved.getUser()).isSameAs(actor);
        assertThat(saved.getActorUserId()).isEqualTo(USER_ID);
        assertThat(saved.getActorEmail()).isNull();
        assertThat(saved.getActorDisplayName()).isNull();
    }

    @Test
    void unsupportedObjectDoesNotUseArbitraryToString() {
        stubRepositorySave();

        Object unsafe = new Object() {
            @Override
            public String toString() {
                return "password=secret";
            }
        };

        auditEventService.recordSystem(
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("object", unsafe)
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        String value =
                (String) captor.getValue()
                        .getDetails()
                        .get("object");

        assertThat(value)
                .startsWith("[UNSUPPORTED_TYPE:")
                .doesNotContain("password=secret");
    }

    @Test
    void sensitiveDetailsAreRedacted() {
        stubRepositorySave();

        auditEventService.recordSystem(
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of(
                        "accessToken", "secret-token",
                        "email", "admin@test.com"
                )
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        assertThat(captor.getValue().getDetails())
                .containsEntry("accessToken", "[REDACTED]")
                .containsEntry("email", "admin@test.com");
    }

    @Test
    void repositoryFailureIsSwallowed() {
        UserEntity actor = actor(
                "admin@test.com",
                "Admin"
        );

        when(userRepository.findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        )).thenReturn(Optional.of(actor));

        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenThrow(new RuntimeException("db error"));

        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of()
        );

        verify(userRepository).findByIdAndOrganizationId(
                USER_ID,
                ORGANIZATION_ID
        );
        verify(auditEventRepository)
                .save(any(AuditEventEntity.class));
    }

    @Test
    void invalidRequiredArgumentsDoNotSave() {
        auditEventService.record(
                USER_ID,
                null,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of()
        );

        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                null,
                Map.of()
        );

        verifyNoInteractions(
                userRepository,
                auditEventRepository
        );
    }

    @Test
    void nullUserIdDoesNotQueryUserRepository() {
        stubRepositorySave();

        auditEventService.record(
                null,
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of()
        );

        verifyNoInteractions(userRepository);
        verify(auditEventRepository)
                .save(any(AuditEventEntity.class));
    }

    private UserEntity actor(
            String email,
            String fullName
    ) {
        UserEntity actor = new UserEntity();
        actor.setId(USER_ID);
        actor.setEmail(email);
        actor.setFullName(fullName);
        return actor;
    }

    private void stubRepositorySave() {
        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(invocation -> {
                    AuditEventEntity event =
                            invocation.getArgument(0);

                    if (event.getId() == null) {
                        event.setId(UUID.randomUUID());
                    }

                    if (event.getCreatedAt() == null) {
                        event.setCreatedAt(
                                Instant.parse(
                                        "2026-06-12T12:00:00Z"
                                )
                        );
                    }

                    return event;
                });
    }
}