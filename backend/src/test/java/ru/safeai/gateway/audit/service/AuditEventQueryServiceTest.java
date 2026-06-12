package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditEventQueryServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID AUDIT_EVENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private AuditEventRepository auditEventRepository;

    private AuditEventQueryService auditEventQueryService;

    @BeforeEach
    void setUp() {
        auditEventQueryService = new AuditEventQueryService(auditEventRepository);
    }

    @Test
    void findAll_shouldReturnAuditEvents() {
        AuditEventEntity event = auditEventEntity();

        when(auditEventRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(event));

        List<AuditEventResponse> response = auditEventQueryService.findAll();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().id()).isEqualTo(AUDIT_EVENT_ID);
        assertThat(response.getFirst().userId()).isEqualTo(USER_ID);
        assertThat(response.getFirst().userEmail()).isEqualTo("admin@test.com");
        assertThat(response.getFirst().eventType()).isEqualTo(AuditEventType.USER_LOGIN_SUCCESS);
        assertThat(response.getFirst().details()).containsEntry("email", "admin@test.com");
        assertThat(response.getFirst().createdAt()).isEqualTo(Instant.parse("2026-06-12T12:00:00Z"));

        verify(auditEventRepository).findAllByOrderByCreatedAtDesc();
        verifyNoMoreInteractions(auditEventRepository);
    }

    @Test
    void findByUserId_shouldReturnUserAuditEvents() {
        AuditEventEntity event = auditEventEntity();

        when(auditEventRepository.findByUser_IdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(event));

        List<AuditEventResponse> response = auditEventQueryService.findByUserId(USER_ID);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().id()).isEqualTo(AUDIT_EVENT_ID);
        assertThat(response.getFirst().userId()).isEqualTo(USER_ID);
        assertThat(response.getFirst().userEmail()).isEqualTo("admin@test.com");

        verify(auditEventRepository).findByUser_IdOrderByCreatedAtDesc(USER_ID);
        verifyNoMoreInteractions(auditEventRepository);
    }

    @Test
    void findAll_shouldReturnAuditEventWithoutUserWhenUserIsNull() {
        AuditEventEntity event = auditEventEntity();
        event.setUser(null);

        when(auditEventRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(event));

        List<AuditEventResponse> response = auditEventQueryService.findAll();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().id()).isEqualTo(AUDIT_EVENT_ID);
        assertThat(response.getFirst().userId()).isNull();
        assertThat(response.getFirst().userEmail()).isNull();
    }

    private AuditEventEntity auditEventEntity() {
        AuditEventEntity event = new AuditEventEntity();
        event.setId(AUDIT_EVENT_ID);
        event.setUser(userEntity());
        event.setEventType(AuditEventType.USER_LOGIN_SUCCESS);
        event.setDetails(Map.of("email", "admin@test.com"));
        event.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));

        return event;
    }

    private UserEntity userEntity() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setEmail("admin@test.com");
        user.setEnabled(true);

        return user;
    }
}