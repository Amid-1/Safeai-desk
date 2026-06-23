package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventQueryServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ADMIN_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    void findAll_whenAdmin_shouldReturnOrganizationScopedAuditEvents() {
        Pageable pageable = PageRequest.of(0, 50);
        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(pageable, event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(adminPrincipal(), emptyFilter(), pageable);

        assertAuditEvent(response);

        verifyFindAll(pageable);
    }

    @Test
    void findAll_whenSuperAdmin_shouldReturnGlobalAuditEvents() {
        Pageable pageable = PageRequest.of(0, 50);
        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(pageable, event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(superAdminPrincipal(), emptyFilter(), pageable);

        assertAuditEvent(response);

        verifyFindAll(pageable);
    }

    @Test
    void findAll_whenSuperAdminWithOrganizationFilter_shouldReturnOrganizationScopedAuditEvents() {
        Pageable pageable = PageRequest.of(0, 50);
        AuditEventEntity event = auditEventEntity();

        AuditEventFilter filter = new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                ORGANIZATION_ID
        );

        whenFindAllReturns(pageable, event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(superAdminPrincipal(), filter, pageable);

        assertAuditEvent(response);

        verifyFindAll(pageable);
    }

    @Test
    void findByUserId_whenAdmin_shouldReturnOrganizationScopedUserAuditEvents() {
        Pageable pageable = PageRequest.of(0, 50);
        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(pageable, event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findByUserId(
                        USER_ID,
                        adminPrincipal(),
                        pageable
                );

        assertAuditEvent(response);

        verifyFindAll(pageable);
    }

    @Test
    void findByUserId_whenSuperAdmin_shouldReturnGlobalUserAuditEvents() {
        Pageable pageable = PageRequest.of(0, 50);
        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(pageable, event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findByUserId(
                        USER_ID,
                        superAdminPrincipal(),
                        pageable
                );

        assertAuditEvent(response);

        verifyFindAll(pageable);
    }

    @Test
    void findAll_shouldReturnAuditEventWithoutUserWhenUserIsNull() {
        Pageable pageable = PageRequest.of(0, 50);
        AuditEventEntity event = auditEventEntity();
        event.setUser(null);

        whenFindAllReturns(pageable, event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(adminPrincipal(), emptyFilter(), pageable);

        assertThat(response.getContent()).hasSize(1);

        AuditEventResponse item = response.getContent().getFirst();

        assertThat(item.id()).isEqualTo(AUDIT_EVENT_ID);
        assertThat(item.userId()).isNull();
        assertThat(item.userEmail()).isNull();
        assertThat(item.organizationId()).isEqualTo(ORGANIZATION_ID);

        verifyFindAll(pageable);
    }

    @SuppressWarnings("unchecked")
    private void whenFindAllReturns(Pageable pageable, AuditEventEntity event) {
        when(auditEventRepository.findAll(
                any(Specification.class),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(event)));
    }

    @SuppressWarnings("unchecked")
    private void verifyFindAll(Pageable pageable) {
        verify(auditEventRepository).findAll(
                any(Specification.class),
                eq(pageable)
        );

        verifyNoMoreInteractions(auditEventRepository);
    }

    private void assertAuditEvent(Page<AuditEventResponse> response) {
        assertThat(response.getContent()).hasSize(1);

        AuditEventResponse item = response.getContent().getFirst();

        assertThat(item.id()).isEqualTo(AUDIT_EVENT_ID);
        assertThat(item.userId()).isEqualTo(USER_ID);
        assertThat(item.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(item.userEmail()).isEqualTo("admin@test.com");
        assertThat(item.eventType()).isEqualTo(AuditEventType.USER_LOGIN_SUCCESS.name());
        assertThat(item.details()).containsEntry("email", "admin@test.com");
        assertThat(item.createdAt()).isEqualTo(Instant.parse("2026-06-12T12:00:00Z"));
    }

    private AuditEventFilter emptyFilter() {
        return new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private AuditEventEntity auditEventEntity() {
        AuditEventEntity event = new AuditEventEntity();
        event.setId(AUDIT_EVENT_ID);
        event.setUser(userEntity());
        event.setOrganizationId(ORGANIZATION_ID);
        event.setEventType(AuditEventType.USER_LOGIN_SUCCESS.name());
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

    private SafeAiUserPrincipal adminPrincipal() {
        return new SafeAiUserPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return new SafeAiUserPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                "",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
    }
}