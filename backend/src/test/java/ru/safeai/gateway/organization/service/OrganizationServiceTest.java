package ru.safeai.gateway.organization.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.UpdateOrganizationEnabledRequest;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.organization.repository.OrganizationImpactQueryRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final Instant CREATED_AT =
            Instant.parse("2026-06-12T12:00:00Z");

    private static final Instant UPDATED_AT =
            Instant.parse("2026-06-13T12:00:00Z");

    private static final long AUTH_VERSION = 7L;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationImpactQueryRepository
            impactQueryRepository;

    @Mock
    private AuditEventService auditEventService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserSessionRevocationService
            userSessionRevocationService;

    @Mock
    private EntityManager entityManager;

    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationService(
                organizationRepository,
                impactQueryRepository,
                auditEventService,
                eventPublisher,
                new PlatformProperties(
                        PLATFORM_ORGANIZATION_ID
                ),
                userSessionRevocationService,
                entityManager
        );

    }

    @Test
    void createCreatesOrganizationForSuperAdminAndReturnsDbTimestamps() {
        stubDatabaseRefresh();

        when(organizationRepository.existsByNormalizedName(
                "safeai"
        )).thenReturn(false);

        when(organizationRepository.saveAndFlush(
                any(OrganizationEntity.class)
        )).thenAnswer(invocation -> {
            OrganizationEntity entity =
                    invocation.getArgument(0);

            entity.setId(ORGANIZATION_ID);
            return entity;
        });

        SafeAiUserPrincipal currentUser =
                superAdminPrincipal();

        OrganizationResponse response =
                organizationService.create(
                        new CreateOrganizationRequest(
                                " SafeAI "
                        ),
                        currentUser
                );

        assertThat(response.id())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(response.name())
                .isEqualTo("SafeAI");

        assertThat(response.enabled())
                .isTrue();

        assertThat(response.type())
                .isEqualTo(
                        ru.safeai.gateway.organization.dto
                                .OrganizationType.TENANT
                );

        assertThat(response.protectedOrganization())
                .isFalse();

        assertThat(response.version())
                .isZero();

        assertThat(response.createdAt())
                .isEqualTo(CREATED_AT);

        assertThat(response.updatedAt())
                .isEqualTo(UPDATED_AT);

        ArgumentCaptor<OrganizationEntity> entityCaptor =
                ArgumentCaptor.forClass(
                        OrganizationEntity.class
                );

        verify(organizationRepository)
                .saveAndFlush(entityCaptor.capture());

        OrganizationEntity saved =
                entityCaptor.getValue();

        assertThat(saved.getName())
                .isEqualTo("SafeAI");

        assertThat(saved.isEnabled())
                .isTrue();

        assertThat(saved.getAuthVersion())
                .isZero();

        verify(entityManager).refresh(saved);

        verify(auditEventService).record(
                same(currentUser),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.ORGANIZATION_CREATED),
                argThat(
                        (Map<String, Object> details) ->
                                Long.valueOf(0L).equals(
                                        details.get(
                                                "authVersion"
                                        )
                                )
                )
        );

        verifyNoInteractions(
                userSessionRevocationService,
                eventPublisher
        );
    }

    @Test
    void createRejectsAdmin() {
        assertThatThrownBy(() ->
                organizationService.create(
                        new CreateOrganizationRequest(
                                "SafeAI"
                        ),
                        adminPrincipal()
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining("SUPER_ADMIN");

        verifyNoInteractions(
                organizationRepository,
                auditEventService,
                eventPublisher,
                userSessionRevocationService
        );
    }

    @Test
    void createRejectsDuplicateNormalizedName() {
        when(organizationRepository.existsByNormalizedName(
                "safeai"
        )).thenReturn(true);

        assertThatThrownBy(() ->
                organizationService.create(
                        new CreateOrganizationRequest(
                                "  SAFEAI "
                        ),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(
                        "уже существует"
                );

        verify(
                organizationRepository,
                never()
        ).saveAndFlush(any());

        verifyNoInteractions(
                auditEventService,
                eventPublisher,
                userSessionRevocationService
        );
    }

    @Test
    void adminFindAllReturnsOnlyOwnOrganization() {
        when(organizationRepository.findById(
                ORGANIZATION_ID
        )).thenReturn(
                Optional.of(organization())
        );

        Page<OrganizationResponse> page =
                organizationService.findAll(
                        adminPrincipal(),
                        PageRequest.of(0, 20)
                );

        assertThat(page.getContent())
                .singleElement()
                .extracting(OrganizationResponse::id)
                .isEqualTo(ORGANIZATION_ID);

        verify(organizationRepository)
                .findById(ORGANIZATION_ID);

        verify(
                organizationRepository,
                never()
        ).findAllStable(any());
    }

    @Test
    void adminFindAllReturnsEmptyForFollowingPages() {
        Page<OrganizationResponse> page =
                organizationService.findAll(
                        adminPrincipal(),
                        PageRequest.of(1, 20)
                );

        assertThat(page).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);

        verifyNoInteractions(
                organizationRepository
        );
    }

    @Test
    void superAdminFindAllUsesStableIdTieBreaker() {
        when(organizationRepository.findAllStable(
                any(Pageable.class)
        )).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);

            return new PageImpl<>(
                    List.of(organization()),
                    pageable,
                    1
            );
        });

        organizationService.findAll(
                superAdminPrincipal(),
                PageRequest.of(0, 20)
        );

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(organizationRepository)
                .findAllStable(pageableCaptor.capture());

        Pageable used = pageableCaptor.getValue();

        assertThat(used.getSort().getOrderFor("createdAt"))
                .isNotNull();

        assertThat(used.getSort().getOrderFor("id"))
                .isNotNull();
    }

    @Test
    void adminCanReadOwnOrganization() {
        when(organizationRepository.findById(
                ORGANIZATION_ID
        )).thenReturn(
                Optional.of(organization())
        );

        OrganizationResponse response =
                organizationService.findById(
                        ORGANIZATION_ID,
                        adminPrincipal()
                );

        assertThat(response.id())
                .isEqualTo(ORGANIZATION_ID);
    }

    @Test
    void adminCannotDiscoverAnotherOrganization() {
        assertThatThrownBy(() ->
                organizationService.findById(
                        OTHER_ORGANIZATION_ID,
                        adminPrincipal()
                )
        )
                .isInstanceOf(
                        ResourceNotFoundException.class
                );

        verifyNoInteractions(
                organizationRepository
        );
    }

    @Test
    void updateNameRenamesNormalOrganization() {
        stubDatabaseRefresh();

        OrganizationEntity organization = organization();

        when(organizationRepository
                .findByIdForSecurityUpdate(
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(organization));

        when(organizationRepository
                .existsByNormalizedNameAndIdNot(
                        "new safeai",
                        ORGANIZATION_ID
                ))
                .thenReturn(false);

        when(organizationRepository.saveAndFlush(
                organization
        )).thenReturn(organization);

        SafeAiUserPrincipal currentUser =
                superAdminPrincipal();

        OrganizationResponse response =
                organizationService.updateName(
                        ORGANIZATION_ID,
                        new UpdateOrganizationRequest(
                                " New   SafeAI "
                        ),
                        currentUser
                );

        assertThat(response.name())
                .isEqualTo("New SafeAI");

        verify(auditEventService).record(
                same(currentUser),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .ORGANIZATION_NAME_CHANGED
                ),
                argThat(
                        (Map<String, Object> details) ->
                                "SafeAI".equals(
                                        details.get(
                                                "oldName"
                                        )
                                )
                                        && "New SafeAI".equals(
                                        details.get(
                                                "newName"
                                        )
                                )
                )
        );
    }

    @Test
    void updateNameRejectsPlatformOrganization() {
        assertThatThrownBy(() ->
                organizationService.updateName(
                        PLATFORM_ORGANIZATION_ID,
                        new UpdateOrganizationRequest(
                                "Renamed Platform"
                        ),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining(
                        "Платформенную организацию"
                );

        verifyNoInteractions(
                organizationRepository
        );
    }

    @Test
    void updateEnabledDisablesOrganizationIncrementsEpochAndRevokesSessions() {
        stubDatabaseRefresh();

        OrganizationEntity organization = organization();

        when(organizationRepository
                .findByIdForSecurityUpdate(
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(organization));

        when(organizationRepository.saveAndFlush(
                organization
        )).thenReturn(organization);

        SafeAiUserPrincipal currentUser =
                superAdminPrincipal();

        OrganizationResponse response =
                organizationService.updateEnabled(
                        ORGANIZATION_ID,
                        new UpdateOrganizationEnabledRequest(
                                false
                        ),
                        currentUser
                );

        assertThat(response.enabled()).isFalse();

        assertThat(organization.getAuthVersion())
                .isEqualTo(AUTH_VERSION + 1);

        verify(userSessionRevocationService)
                .revokeAllForOrganization(
                        ORGANIZATION_ID,
                        RefreshTokenRevocationReason
                                .ORGANIZATION_DISABLED
                );

        ArgumentCaptor<
                OrganizationSecurityStateChangedEvent>
                eventCaptor = ArgumentCaptor.forClass(
                OrganizationSecurityStateChangedEvent.class
        );

        verify(eventPublisher)
                .publishEvent(eventCaptor.capture());

        assertThat(eventCaptor.getValue().organizationId())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(eventCaptor.getValue().authVersion())
                .isEqualTo(AUTH_VERSION + 1);

        verify(auditEventService).record(
                same(currentUser),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .ORGANIZATION_ENABLED_CHANGED
                ),
                argThat(
                        (Map<String, Object> details) ->
                                Long.valueOf(AUTH_VERSION)
                                        .equals(
                                                details.get(
                                                        "oldAuthVersion"
                                                )
                                        )
                                        && Long.valueOf(
                                        AUTH_VERSION + 1
                                ).equals(
                                        details.get(
                                                "newAuthVersion"
                                        )
                                )
                                        && Boolean.TRUE.equals(
                                        details.get(
                                                "sessionsRevoked"
                                        )
                                )
                )
        );
    }

    @Test
    void updateEnabledReEnablesWithoutDecreasingEpochOrRestoringSessions() {
        stubDatabaseRefresh();

        OrganizationEntity organization = organization();
        organization.setEnabled(false);

        when(organizationRepository
                .findByIdForSecurityUpdate(
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(organization));

        when(organizationRepository.saveAndFlush(
                organization
        )).thenReturn(organization);

        OrganizationResponse response =
                organizationService.updateEnabled(
                        ORGANIZATION_ID,
                        new UpdateOrganizationEnabledRequest(
                                true
                        ),
                        superAdminPrincipal()
                );

        assertThat(response.enabled()).isTrue();

        assertThat(organization.getAuthVersion())
                .isEqualTo(AUTH_VERSION);

        verify(
                userSessionRevocationService,
                never()
        ).revokeAllForOrganization(
                any(UUID.class),
                any(RefreshTokenRevocationReason.class)
        );

        verify(eventPublisher)
                .publishEvent(
                        new OrganizationSecurityStateChangedEvent(
                                ORGANIZATION_ID,
                                AUTH_VERSION
                        )
                );
    }

    @Test
    void updateEnabledReturnsWithoutSideEffectsWhenValueIsUnchanged() {
        OrganizationEntity organization = organization();

        when(organizationRepository
                .findByIdForSecurityUpdate(
                        ORGANIZATION_ID
                ))
                .thenReturn(Optional.of(organization));

        OrganizationResponse response =
                organizationService.updateEnabled(
                        ORGANIZATION_ID,
                        new UpdateOrganizationEnabledRequest(
                                true
                        ),
                        superAdminPrincipal()
                );

        assertThat(response.enabled()).isTrue();

        verify(
                organizationRepository,
                never()
        ).saveAndFlush(any());

        verifyNoInteractions(
                userSessionRevocationService,
                eventPublisher,
                auditEventService
        );
    }

    @Test
    void updateEnabledRejectsPlatformOrganization() {
        assertThatThrownBy(() ->
                organizationService.updateEnabled(
                        PLATFORM_ORGANIZATION_ID,
                        new UpdateOrganizationEnabledRequest(
                                false
                        ),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining(
                        "Платформенную организацию"
                );

        verifyNoInteractions(
                organizationRepository,
                userSessionRevocationService,
                eventPublisher,
                auditEventService
        );
    }

    private void stubDatabaseRefresh() {
        doAnswer(invocation -> {
            OrganizationEntity entity =
                    invocation.getArgument(0);

            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(CREATED_AT);
            }

            entity.setUpdatedAt(UPDATED_AT);
            return null;
        }).when(entityManager).refresh(
                any(OrganizationEntity.class)
        );
    }

    private OrganizationEntity organization() {
        OrganizationEntity entity =
                new OrganizationEntity();

        entity.setId(ORGANIZATION_ID);
        entity.setName("SafeAI");
        entity.setEnabled(true);
        entity.setAuthVersion(AUTH_VERSION);
        entity.setCreatedAt(CREATED_AT);
        entity.setUpdatedAt(UPDATED_AT);

        return entity;
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        ADMIN_ID,
                        ORGANIZATION_ID,
                        "admin@test.com",
                        0L,
                        AUTH_VERSION,
                        Set.of(
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
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }
}