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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.dto.AuditEventResponse;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditEventQueryServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ADMIN_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final UUID FOREIGN_ORGANIZATION_ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID AUDIT_EVENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final Sort DEFAULT_AUDIT_SORT = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")
    );

    @Mock
    private AuditEventRepository auditEventRepository;

    private AuditEventQueryService auditEventQueryService;

    @BeforeEach
    void setUp() {
        auditEventQueryService = new AuditEventQueryService(auditEventRepository);
    }

    @Test
    void findAll_whenAdmin_shouldReturnOrganizationScopedAuditEvents() {
        Pageable requestedPageable = PageRequest.of(0, 50);
        Pageable expectedPageable = PageRequest.of(0, 50, DEFAULT_AUDIT_SORT);

        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(
                        adminPrincipal(),
                        emptyFilter(),
                        requestedPageable
                );

        assertAuditEvent(response);

        verifyFindAll(expectedPageable);
    }

    @Test
    void findAll_whenSuperAdmin_shouldReturnGlobalAuditEvents() {
        Pageable requestedPageable = PageRequest.of(0, 50);
        Pageable expectedPageable = PageRequest.of(0, 50, DEFAULT_AUDIT_SORT);

        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(
                        superAdminPrincipal(),
                        emptyFilter(),
                        requestedPageable
                );

        assertAuditEvent(response);

        verifyFindAll(expectedPageable);
    }

    @Test
    void findAll_whenSuperAdminWithOrganizationFilter_shouldReturnOrganizationScopedAuditEvents() {
        Pageable requestedPageable = PageRequest.of(0, 50);
        Pageable expectedPageable = PageRequest.of(0, 50, DEFAULT_AUDIT_SORT);

        AuditEventEntity event = auditEventEntity();

        AuditEventFilter filter = new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                ORGANIZATION_ID
        );

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(
                        superAdminPrincipal(),
                        filter,
                        requestedPageable
                );

        assertAuditEvent(response);

        verifyFindAll(expectedPageable);
    }

    @Test
    void findAll_whenAdminWithOwnOrganizationFilter_shouldReturnOrganizationScopedAuditEvents() {
        Pageable requestedPageable = PageRequest.of(0, 50);
        Pageable expectedPageable = PageRequest.of(0, 50, DEFAULT_AUDIT_SORT);

        AuditEventEntity event = auditEventEntity();

        AuditEventFilter filter = new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                ORGANIZATION_ID
        );

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(
                        adminPrincipal(),
                        filter,
                        requestedPageable
                );

        assertAuditEvent(response);

        verifyFindAll(expectedPageable);
    }

    @Test
    void findAll_whenAdminWithForeignOrganizationFilter_shouldThrowForbiddenOperationException() {
        Pageable requestedPageable = PageRequest.of(0, 50);

        AuditEventFilter filter = new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                FOREIGN_ORGANIZATION_ID
        );

        assertThatThrownBy(() -> auditEventQueryService.findAll(
                adminPrincipal(),
                filter,
                requestedPageable
        )).isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Нельзя фильтровать аудит другой организации");

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    void findAll_whenUnsafeSortAndLargePageSize_shouldUseDefaultSortAndCapPageSize() {
        Pageable requestedPageable = PageRequest.of(
                0,
                200,
                Sort.by("user.passwordHash")
        );

        Pageable expectedPageable = PageRequest.of(
                0,
                100,
                DEFAULT_AUDIT_SORT
        );

        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(
                        adminPrincipal(),
                        emptyFilter(),
                        requestedPageable
                );

        assertAuditEvent(response);

        verifyFindAll(expectedPageable);
    }

    @Test
    void findAll_whenAllowedSort_shouldKeepAllowedSort() {
        Pageable requestedPageable = PageRequest.of(
                0,
                50,
                Sort.by(Sort.Direction.ASC, "eventType")
        );

        Pageable expectedPageable = PageRequest.of(
                0,
                50,
                Sort.by(
                        Sort.Order.asc("eventType"),
                        Sort.Order.desc("id")
                )
        );

        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(
                        adminPrincipal(),
                        emptyFilter(),
                        requestedPageable
                );

        assertAuditEvent(response);

        verifyFindAll(expectedPageable);
    }


    @Test
    void findAll_whenDateRangeIsInvalid_shouldThrowBadRequestException() {
        assertThatThrownBy(() -> {
            AuditEventFilter filter = new AuditEventFilter(
                    null,
                    null,
                    null,
                    Instant.parse("2026-07-12T12:00:00Z"),
                    Instant.parse("2026-07-12T12:00:00Z"),
                    null
            );

            auditEventQueryService.findAll(
                    adminPrincipal(),
                    filter,
                    PageRequest.of(0, 50)
            );
        })
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dateFrom должен быть раньше dateTo");

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    void findByUserId_whenAdmin_shouldReturnOrganizationScopedUserAuditEvents() {
        Pageable requestedPageable = PageRequest.of(0, 50);
        Pageable expectedPageable = PageRequest.of(0, 50, DEFAULT_AUDIT_SORT);

        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findByUserId(
                        USER_ID,
                        adminPrincipal(),
                        requestedPageable
                );

        assertAuditEvent(response);

        verifyFindAll(expectedPageable);
    }

    @Test
    void findByUserId_whenSuperAdmin_shouldReturnGlobalUserAuditEvents() {
        Pageable requestedPageable = PageRequest.of(0, 50);
        Pageable expectedPageable = PageRequest.of(0, 50, DEFAULT_AUDIT_SORT);

        AuditEventEntity event = auditEventEntity();

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findByUserId(
                        USER_ID,
                        superAdminPrincipal(),
                        requestedPageable
                );

        assertAuditEvent(response);

        verifyFindAll(expectedPageable);
    }

    @Test
    void findAll_shouldReturnAuditEventWithoutUserWhenUserIsNull() {
        Pageable requestedPageable = PageRequest.of(0, 50);
        Pageable expectedPageable = PageRequest.of(0, 50, DEFAULT_AUDIT_SORT);

        AuditEventEntity event = auditEventEntity();
        event.setUser(null);
        event.setActorUserId(null);
        event.setActorEmail(null);
        event.setActorDisplayName(null);

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                auditEventQueryService.findAll(
                        adminPrincipal(),
                        emptyFilter(),
                        requestedPageable
                );

        assertThat(response.getContent()).hasSize(1);

        AuditEventResponse item = response.getContent().getFirst();

        assertThat(item.id()).isEqualTo(AUDIT_EVENT_ID);
        assertThat(item.userId()).isNull();
        assertThat(item.userEmail()).isNull();
        assertThat(item.organizationId()).isEqualTo(ORGANIZATION_ID);

        verifyFindAll(expectedPageable);
    }

    @SuppressWarnings("unchecked")
    private void whenFindAllReturns(AuditEventEntity event) {
        when(auditEventRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(event)));
    }

    @SuppressWarnings("unchecked")
    private void verifyFindAll(Pageable expectedPageable) {
        verify(auditEventRepository).findAll(
                any(Specification.class),
                eq(expectedPageable)
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
        event.setActorUserId(USER_ID);
        event.setActorEmail("admin@test.com");
        event.setActorDisplayName("Admin");
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