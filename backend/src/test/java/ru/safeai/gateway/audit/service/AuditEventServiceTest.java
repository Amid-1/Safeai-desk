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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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
    void record_shouldSaveAuditEventWithUserWhenUserExists() {
        UserEntity user = userEntity();

        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user));

        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(invocation -> persistAuditEvent(invocation.getArgument(0)));

        auditEventService.record(
                USER_ID,
                AuditEventType.USER_LOGIN_SUCCESS,
                Map.of("email", "admin@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity savedEvent = captor.getValue();

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getUser()).isEqualTo(user);
        assertThat(savedEvent.getEventType()).isEqualTo(AuditEventType.USER_LOGIN_SUCCESS);
        assertThat(savedEvent.getDetails()).containsEntry("email", "admin@test.com");
        assertThat(savedEvent.getCreatedAt()).isNotNull();

        verify(userRepository).findById(USER_ID);
    }

    @Test
    void record_shouldSaveAuditEventWithoutUserWhenUserIdIsNull() {
        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(invocation -> persistAuditEvent(invocation.getArgument(0)));

        auditEventService.record(
                null,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("email", "unknown@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity savedEvent = captor.getValue();

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getUser()).isNull();
        assertThat(savedEvent.getEventType()).isEqualTo(AuditEventType.USER_LOGIN_FAILED);
        assertThat(savedEvent.getDetails()).containsEntry("email", "unknown@test.com");
        assertThat(savedEvent.getCreatedAt()).isNotNull();

        verifyNoInteractions(userRepository);
    }

    @Test
    void record_shouldSaveAuditEventWithoutUserWhenUserDoesNotExist() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.empty());

        when(auditEventRepository.save(any(AuditEventEntity.class)))
                .thenAnswer(invocation -> persistAuditEvent(invocation.getArgument(0)));

        auditEventService.record(
                USER_ID,
                AuditEventType.USER_LOGIN_FAILED,
                Map.of("email", "admin@test.com")
        );

        ArgumentCaptor<AuditEventEntity> captor =
                ArgumentCaptor.forClass(AuditEventEntity.class);

        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity savedEvent = captor.getValue();

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getUser()).isNull();
        assertThat(savedEvent.getEventType()).isEqualTo(AuditEventType.USER_LOGIN_FAILED);
        assertThat(savedEvent.getCreatedAt()).isNotNull();
    }

    private UserEntity userEntity() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail("admin@test.com");
        user.setEnabled(true);

        return user;
    }

    private AuditEventEntity persistAuditEvent(AuditEventEntity event) {
        if (event.getId() == null) {
            event.setId(UUID.randomUUID());
        }

        if (event.getCreatedAt() == null) {
            event.setCreatedAt(java.time.Instant.parse("2026-06-12T12:00:00Z"));
        }

        return event;
    }
}