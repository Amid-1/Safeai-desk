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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventQueryServiceTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID FOREIGN_ORGANIZATION_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000101"
            );

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final UUID AUDIT_EVENT_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final Sort DEFAULT_AUDIT_SORT =
            Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id")
            );

    @Mock
    private AuditEventRepository auditEventRepository;

    private AuditEventQueryService service;

    @BeforeEach
    void setUp() {
        service = new AuditEventQueryService(
                auditEventRepository
        );
    }

    @Test
    void findAll_whenAdmin_shouldUseTenantScopedSpecification() {
        whenFindAllReturns(auditEventEntity());

        Page<AuditEventResponse> response =
                service.findAll(
                        adminPrincipal(),
                        emptyFilter(),
                        PageRequest.of(0, 50)
                );

        assertAuditEvent(response);

        verifyFindAll(
                PageRequest.of(
                        0,
                        50,
                        DEFAULT_AUDIT_SORT
                )
        );
    }

    @Test
    void findAll_whenSuperAdmin_shouldAllowGlobalQuery() {
        whenFindAllReturns(auditEventEntity());

        Page<AuditEventResponse> response =
                service.findAll(
                        superAdminPrincipal(),
                        emptyFilter(),
                        PageRequest.of(0, 50)
                );

        assertAuditEvent(response);

        verifyFindAll(
                PageRequest.of(
                        0,
                        50,
                        DEFAULT_AUDIT_SORT
                )
        );
    }

    @Test
    void findAll_whenSuperAdminFiltersOrganization_shouldProceed() {
        whenFindAllReturns(auditEventEntity());

        AuditEventFilter filter = new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                ORGANIZATION_ID
        );

        Page<AuditEventResponse> response =
                service.findAll(
                        superAdminPrincipal(),
                        filter,
                        PageRequest.of(0, 50)
                );

        assertAuditEvent(response);
        verifyFindAll(
                PageRequest.of(
                        0,
                        50,
                        DEFAULT_AUDIT_SORT
                )
        );
    }

    @Test
    void findAll_whenAdminFiltersOwnOrganization_shouldProceed() {
        whenFindAllReturns(auditEventEntity());

        AuditEventFilter filter = new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                ORGANIZATION_ID
        );

        Page<AuditEventResponse> response =
                service.findAll(
                        adminPrincipal(),
                        filter,
                        PageRequest.of(0, 50)
                );

        assertAuditEvent(response);
        verifyFindAll(
                PageRequest.of(
                        0,
                        50,
                        DEFAULT_AUDIT_SORT
                )
        );
    }

    @Test
    void findAll_whenAdminFiltersForeignOrganization_shouldRejectBeforeRepository() {
        AuditEventFilter filter = new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                FOREIGN_ORGANIZATION_ID
        );

        assertThatThrownBy(() ->
                service.findAll(
                        adminPrincipal(),
                        filter,
                        PageRequest.of(0, 50)
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining(
                        "Нельзя фильтровать аудит другой организации"
                );

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    void findAll_whenUnsupportedSort_shouldReturnBadRequest() {
        Pageable pageable = PageRequest.of(
                0,
                200,
                Sort.by("user.passwordHash")
        );

        assertThatThrownBy(() ->
                service.findAll(
                        adminPrincipal(),
                        emptyFilter(),
                        pageable
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(
                        "Сортировка по полю не разрешена"
                )
                .hasMessageContaining(
                        "user.passwordHash"
                );

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    void findAll_whenPageSizeIsTooLarge_shouldCapIt() {
        whenFindAllReturns(auditEventEntity());

        service.findAll(
                adminPrincipal(),
                emptyFilter(),
                PageRequest.of(0, 200)
        );

        verifyFindAll(
                PageRequest.of(
                        0,
                        100,
                        DEFAULT_AUDIT_SORT
                )
        );
    }

    @Test
    void findAll_whenAllowedSortHasNoId_shouldAppendIdTieBreaker() {
        whenFindAllReturns(auditEventEntity());

        service.findAll(
                adminPrincipal(),
                emptyFilter(),
                PageRequest.of(
                        0,
                        50,
                        Sort.by(
                                Sort.Direction.ASC,
                                "eventType"
                        )
                )
        );

        verifyFindAll(
                PageRequest.of(
                        0,
                        50,
                        Sort.by(
                                Sort.Order.asc("eventType"),
                                Sort.Order.desc("id")
                        )
                )
        );
    }

    @Test
    void findAll_whenAllowedSortAlreadyContainsId_shouldNotAppendDuplicate() {
        whenFindAllReturns(auditEventEntity());

        Sort requestedSort = Sort.by(
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id")
        );

        service.findAll(
                adminPrincipal(),
                emptyFilter(),
                PageRequest.of(
                        0,
                        50,
                        requestedSort
                )
        );

        verifyFindAll(
                PageRequest.of(
                        0,
                        50,
                        requestedSort
                )
        );
    }

    @Test
    void findAll_whenDateRangeIsInvalid_shouldReject() {
        assertThatThrownBy(() -> {
            AuditEventFilter filter = new AuditEventFilter(
                    null,
                    null,
                    null,
                    Instant.parse(
                            "2026-07-12T12:00:00Z"
                    ),
                    Instant.parse(
                            "2026-07-12T12:00:00Z"
                    ),
                    null
            );

            service.findAll(
                    adminPrincipal(),
                    filter,
                    PageRequest.of(0, 50)
            );
        })
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(
                        "dateFrom должен быть раньше dateTo"
                );

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    void findByUserId_whenAdmin_shouldDelegateThroughTenantScopedFindAll() {
        whenFindAllReturns(auditEventEntity());

        Page<AuditEventResponse> response =
                service.findByUserId(
                        USER_ID,
                        adminPrincipal(),
                        PageRequest.of(0, 50)
                );

        assertAuditEvent(response);

        verifyFindAll(
                PageRequest.of(
                        0,
                        50,
                        DEFAULT_AUDIT_SORT
                )
        );
    }

    @Test
    void findByUserId_whenUserIdIsNull_shouldRejectBeforeRepository() {
        assertThatThrownBy(() ->
                service.findByUserId(
                        null,
                        adminPrincipal(),
                        PageRequest.of(0, 50)
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "userId не должен быть null"
                );

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    void findAll_shouldMapSystemEventWithoutActor() {
        AuditEventEntity event = auditEventEntity();
        event.setActorUserId(null);
        event.setActorOrganizationId(null);
        event.setActorEmail(null);
        event.setActorDisplayName("SYSTEM");

        whenFindAllReturns(event);

        Page<AuditEventResponse> response =
                service.findAll(
                        adminPrincipal(),
                        emptyFilter(),
                        PageRequest.of(0, 50)
                );

        AuditEventResponse item =
                response.getContent().getFirst();

        assertThat(item.userId()).isNull();
        assertThat(item.actorOrganizationId()).isNull();
        assertThat(item.userEmail()).isNull();
        assertThat(item.userDisplayName())
                .isEqualTo("SYSTEM");
    }

    @SuppressWarnings("unchecked")
    private void whenFindAllReturns(
            AuditEventEntity event
    ) {
        when(auditEventRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(
                new PageImpl<>(List.of(event))
        );
    }

    @SuppressWarnings("unchecked")
    private void verifyFindAll(
            Pageable expectedPageable
    ) {
        verify(auditEventRepository).findAll(
                any(Specification.class),
                eq(expectedPageable)
        );

        verifyNoMoreInteractions(
                auditEventRepository
        );
    }

    private void assertAuditEvent(
            Page<AuditEventResponse> response
    ) {
        assertThat(response.getContent()).hasSize(1);

        AuditEventResponse item =
                response.getContent().getFirst();

        assertThat(item.id())
                .isEqualTo(AUDIT_EVENT_ID);
        assertThat(item.userId())
                .isEqualTo(USER_ID);
        assertThat(item.actorOrganizationId())
                .isEqualTo(ORGANIZATION_ID);
        assertThat(item.organizationId())
                .isEqualTo(ORGANIZATION_ID);
        assertThat(item.userEmail())
                .isEqualTo("admin@test.com");
        assertThat(item.userDisplayName())
                .isEqualTo("Admin");
        assertThat(item.eventType())
                .isEqualTo(
                        AuditEventType
                                .USER_LOGIN_SUCCESS
                                .name()
                );
        assertThat(item.details())
                .containsEntry(
                        "email",
                        "admin@test.com"
                );
        assertThat(item.createdAt())
                .isEqualTo(
                        Instant.parse(
                                "2026-06-12T12:00:00Z"
                        )
                );
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
        AuditEventEntity event =
                new AuditEventEntity();

        event.setId(AUDIT_EVENT_ID);
        event.setActorUserId(USER_ID);
        event.setActorOrganizationId(
                ORGANIZATION_ID
        );
        event.setActorEmail("admin@test.com");
        event.setActorDisplayName("Admin");
        event.setOrganizationId(ORGANIZATION_ID);
        event.setEventType(
                AuditEventType
                        .USER_LOGIN_SUCCESS
                        .name()
        );
        event.setDetails(
                Map.of(
                        "email",
                        "admin@test.com"
                )
        );
        event.setCreatedAt(
                Instant.parse(
                        "2026-06-12T12:00:00Z"
                )
        );

        return event;
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        ADMIN_ID,
                        ORGANIZATION_ID,
                        "admin@test.com",
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        SUPER_ADMIN_ID,
                        PLATFORM_ORGANIZATION_ID,
                        "superadmin@test.com",
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }
}
