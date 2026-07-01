package ru.safeai.gateway.audit.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private EntityManager entityManager;

    private AuditEventService auditEventService;

    @BeforeEach
    void setUp() {
        auditEventService = new AuditEventService(
                auditEventRepository,
                entityManager
        );
    }

    @Test
    void record_shouldSaveAuditEventWithUserAndOrganization() {
        UserEntity user = userEntity();

        when(entityManager.find(UserEntity.class, USER_ID))
                .thenReturn(user);

        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(invocation -> persistAuditEvent(invocation.getArgument(0)));

        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_SUCCESS,
                Map.of("email", "admin@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity savedEvent = captor.getValue();

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getUser()).isEqualTo(user);
        assertThat(savedEvent.getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(savedEvent.getEventType()).isEqualTo(AuditEventType.USER_LOGIN_SUCCESS.name());
        assertThat(savedEvent.getDetails()).containsEntry("email", "admin@test.com");
        assertThat(savedEvent.getCreatedAt()).isNotNull();

        verify(entityManager).find(UserEntity.class, USER_ID);
        verifyNoMoreInteractions(entityManager, auditEventRepository);
    }

    @Test
    void record_shouldSaveAuditEventWithoutUserWhenUserNotFound() {
        when(entityManager.find(UserEntity.class, USER_ID))
                .thenReturn(null);

        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(invocation -> persistAuditEvent(invocation.getArgument(0)));

        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("email", "missing@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity savedEvent = captor.getValue();

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getUser()).isNull();
        assertThat(savedEvent.getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(savedEvent.getEventType()).isEqualTo(AuditEventType.USER_LOGIN_FAILED.name());
        assertThat(savedEvent.getDetails()).containsEntry("email", "missing@test.com");
        assertThat(savedEvent.getCreatedAt()).isNotNull();

        verify(entityManager).find(UserEntity.class, USER_ID);
        verifyNoMoreInteractions(entityManager, auditEventRepository);
    }

    @Test
    void recordSystem_shouldSaveAuditEventWithoutUserButWithOrganization() {
        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(invocation -> persistAuditEvent(invocation.getArgument(0)));

        auditEventService.recordSystem(
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("email", "unknown@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity savedEvent = captor.getValue();

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getUser()).isNull();
        assertThat(savedEvent.getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(savedEvent.getEventType()).isEqualTo(AuditEventType.USER_LOGIN_FAILED.name());
        assertThat(savedEvent.getDetails()).containsEntry("email", "unknown@test.com");
        assertThat(savedEvent.getCreatedAt()).isNotNull();

        verifyNoInteractions(entityManager);
        verifyNoMoreInteractions(auditEventRepository);
    }

    @Test
    void record_shouldNotThrowWhenRepositoryFails() {
        when(entityManager.find(UserEntity.class, USER_ID))
                .thenReturn(userEntity());

        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenThrow(new RuntimeException("db error"));

        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("email", "admin@test.com")
        );

        verify(entityManager).find(UserEntity.class, USER_ID);
        verify(auditEventRepository).save(any(AuditEventEntity.class));
        verifyNoMoreInteractions(entityManager, auditEventRepository);
    }

    @Test
    void record_shouldNotSaveWhenOrganizationIdIsNull() {
        auditEventService.record(
                USER_ID,
                null,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("email", "admin@test.com")
        );

        verifyNoInteractions(entityManager);
        verifyNoInteractions(auditEventRepository);
    }

    @Test
    void record_shouldNotSaveWhenEventTypeIsNull() {
        auditEventService.record(
                USER_ID,
                ORGANIZATION_ID,
                null,
                Map.of("email", "admin@test.com")
        );

        verifyNoInteractions(entityManager);
        verifyNoInteractions(auditEventRepository);
    }

    private UserEntity userEntity() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Demo Company");
        organization.setEnabled(true);

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail("admin@test.com");
        user.setEnabled(true);
        user.setOrganization(organization);

        return user;
    }

    private AuditEventEntity persistAuditEvent(AuditEventEntity event) {
        if (event.getId() == null) {
            event.setId(UUID.randomUUID());
        }

        if (event.getCreatedAt() == null) {
            event.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        }

        return event;
    }
}