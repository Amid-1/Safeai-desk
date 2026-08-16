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
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.OrganizationVersionConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.DisableOrganizationRequest;
import ru.safeai.gateway.organization.dto.EnableOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationDirectoryResponse;
import ru.safeai.gateway.organization.dto.OrganizationDisableImpactResponse;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.OrganizationType;
import ru.safeai.gateway.organization.dto.UpdateOrganizationEnabledRequest;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.organization.repository.OrganizationImpactQueryRepository;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

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
            Instant.parse(
                    "2026-06-12T12:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-06-13T12:00:00Z"
            );

    private static final long VERSION = 3L;
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
        organizationService =
                new OrganizationService(
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
    void createReturnsVersionedTenantContract() {
        stubCreatePersistence();

        when(
                organizationRepository
                        .existsByNormalizedName(
                                "safeai"
                        )
        ).thenReturn(false);

        OrganizationResponse response =
                organizationService.create(
                        new CreateOrganizationRequest(
                                " SafeAI "
                        ),
                        superAdminPrincipal()
                );

        assertThat(response.id())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(response.name())
                .isEqualTo("SafeAI");

        assertThat(response.enabled())
                .isTrue();

        assertThat(response.type())
                .isEqualTo(
                        OrganizationType.TENANT
                );

        assertThat(
                response.protectedOrganization()
        ).isFalse();

        assertThat(response.version())
                .isZero();

        assertThat(response.createdAt())
                .isEqualTo(CREATED_AT);

        assertThat(response.updatedAt())
                .isEqualTo(UPDATED_AT);

        verify(auditEventService).record(
                any(SafeAiUserPrincipal.class),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .ORGANIZATION_CREATED
                ),
                argThat(
                        (Map<String, Object> details) ->
                                Long.valueOf(0L)
                                        .equals(
                                                details.get(
                                                        "version"
                                                )
                                        )
                )
        );
    }

    @Test
    void createRejectsAdminBeforeRepositoryAccess() {
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
                .hasMessageContaining(
                        "SUPER_ADMIN"
                );

        verifyNoInteractions(
                organizationRepository,
                impactQueryRepository,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                entityManager
        );
    }

    @Test
    void createRejectsDuplicateNormalizedName() {
        when(
                organizationRepository
                        .existsByNormalizedName(
                                "safeai"
                        )
        ).thenReturn(true);

        assertThatThrownBy(() ->
                organizationService.create(
                        new CreateOrganizationRequest(
                                " SAFEAI "
                        ),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(
                        ConflictException.class
                )
                .hasMessageContaining(
                        "уже существует"
                );

        verify(
                organizationRepository,
                never()
        ).saveAndFlush(any());
    }

    @Test
    void adminFindAllReturnsOnlyOwnOrganization() {
        when(
                organizationRepository.findById(
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(
                        tenantOrganization()
                )
        );

        Page<OrganizationResponse> result =
                organizationService.findAll(
                        adminPrincipal(),
                        PageRequest.of(
                                0,
                                20
                        )
                );

        assertThat(result.getContent())
                .singleElement()
                .extracting(
                        OrganizationResponse::id
                )
                .isEqualTo(
                        ORGANIZATION_ID
                );

        verify(
                organizationRepository
        ).findById(
                ORGANIZATION_ID
        );

        verify(
                organizationRepository,
                never()
        ).findAllStable(
                any(Pageable.class)
        );
    }

    @Test
    void adminFindAllReturnsEmptyFollowingPage() {
        Page<OrganizationResponse> result =
                organizationService.findAll(
                        adminPrincipal(),
                        PageRequest.of(
                                1,
                                20
                        )
                );

        assertThat(result).isEmpty();
        assertThat(
                result.getTotalElements()
        ).isEqualTo(1L);

        verifyNoInteractions(
                organizationRepository
        );
    }

    @Test
    void superAdminFindAllAddsStableIdTieBreaker() {
        when(
                organizationRepository.findAllStable(
                        any(Pageable.class)
                )
        ).thenAnswer(invocation -> {
            Pageable pageable =
                    invocation.getArgument(0);

            return new PageImpl<>(
                    List.of(
                            tenantOrganization()
                    ),
                    pageable,
                    1L
            );
        });

        organizationService.findAll(
                superAdminPrincipal(),
                PageRequest.of(
                        0,
                        20,
                        Sort.by(
                                Sort.Order.asc(
                                        "name"
                                )
                        )
                )
        );

        ArgumentCaptor<Pageable> captor =
                ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(
                organizationRepository
        ).findAllStable(
                captor.capture()
        );

        Pageable used =
                captor.getValue();

        assertThat(
                used.getSort()
                        .getOrderFor("name")
        ).isNotNull();

        Sort.Order idOrder =
                used.getSort()
                        .getOrderFor("id");

        assertThat(idOrder)
                .isNotNull();

        assertThat(idOrder.getDirection())
                .isEqualTo(
                        Sort.Direction.ASC
                );
    }

    @Test
    void findAllRejectsOversizedPage() {
        assertThatThrownBy(() ->
                organizationService.findAll(
                        superAdminPrincipal(),
                        PageRequest.of(
                                0,
                                101
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "100"
                );

        verifyNoInteractions(
                organizationRepository
        );
    }

    @Test
    void findAllRejectsUnsupportedSort() {
        assertThatThrownBy(() ->
                organizationService.findAll(
                        superAdminPrincipal(),
                        PageRequest.of(
                                0,
                                20,
                                Sort.by("authVersion")
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "authVersion"
                );

        verifyNoInteractions(
                organizationRepository
        );
    }

    @Test
    void directoryClampsLimitAndReturnsTenantMetadata() {
        when(
                organizationRepository.findAll(
                        any(Pageable.class)
                )
        ).thenAnswer(invocation -> {
            Pageable pageable =
                    invocation.getArgument(0);

            return new PageImpl<>(
                    List.of(
                            tenantOrganization()
                    ),
                    pageable,
                    1L
            );
        });

        List<OrganizationDirectoryResponse> result =
                organizationService.findDirectory(
                        "",
                        500,
                        superAdminPrincipal()
                );

        assertThat(result)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.id())
                            .isEqualTo(
                                    ORGANIZATION_ID
                            );
                    assertThat(item.type())
                            .isEqualTo(
                                    OrganizationType.TENANT
                            );
                    assertThat(
                            item.protectedOrganization()
                    ).isFalse();
                    assertThat(item.version())
                            .isEqualTo(VERSION);
                });

        ArgumentCaptor<Pageable> captor =
                ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(
                organizationRepository
        ).findAll(
                captor.capture()
        );

        assertThat(
                captor.getValue()
                        .getPageSize()
        ).isEqualTo(50);
    }

    @Test
    void directoryExactPlatformIdReturnsProtectedPlatform() {
        when(
                organizationRepository.findById(
                        PLATFORM_ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(
                        platformOrganization()
                )
        );

        List<OrganizationDirectoryResponse> result =
                organizationService.findDirectory(
                        PLATFORM_ORGANIZATION_ID
                                .toString(),
                        20,
                        superAdminPrincipal()
                );

        assertThat(result)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.type())
                            .isEqualTo(
                                    OrganizationType.PLATFORM
                            );
                    assertThat(
                            item.protectedOrganization()
                    ).isTrue();
                });

        verify(
                organizationRepository,
                never()
        ).searchDirectoryByName(
                any(),
                any()
        );
    }

    @Test
    void directoryNameSearchUsesBoundedPageable() {
        when(
                organizationRepository
                        .searchDirectoryByName(
                                eq("Demo"),
                                any(Pageable.class)
                        )
        ).thenAnswer(invocation -> {
            Pageable pageable =
                    invocation.getArgument(1);

            return new PageImpl<>(
                    List.of(
                            tenantOrganization()
                    ),
                    pageable,
                    1L
            );
        });

        organizationService.findDirectory(
                " Demo ",
                20,
                superAdminPrincipal()
        );

        verify(
                organizationRepository
        ).searchDirectoryByName(
                eq("Demo"),
                any(Pageable.class)
        );
    }

    @Test
    void directoryRejectsAdmin() {
        assertThatThrownBy(() ->
                organizationService.findDirectory(
                        "",
                        20,
                        adminPrincipal()
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                );

        verifyNoInteractions(
                organizationRepository
        );
    }

    @Test
    void adminCanReadOwnOrganization() {
        when(
                organizationRepository.findById(
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(
                        tenantOrganization()
                )
        );

        OrganizationResponse result =
                organizationService.findById(
                        ORGANIZATION_ID,
                        adminPrincipal()
                );

        assertThat(result.id())
                .isEqualTo(
                        ORGANIZATION_ID
                );
    }

    @Test
    void adminCannotDiscoverForeignOrganization() {
        assertThatThrownBy(() ->
                organizationService.findById(
                        OTHER_ORGANIZATION_ID,
                        adminPrincipal()
                )
        ).isInstanceOf(
                ResourceNotFoundException.class
        );

        verifyNoInteractions(
                organizationRepository
        );
    }

    @Test
    void updateNameUsesExpectedVersionAndIncrementsVersion() {
        OrganizationEntity entity =
                tenantOrganization();

        stubUpdatePersistence(entity);

        when(
                organizationRepository
                        .findByIdForSecurityUpdate(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        when(
                organizationRepository
                        .existsByNormalizedNameAndIdNot(
                                "new safeai",
                                ORGANIZATION_ID
                        )
        ).thenReturn(false);

        OrganizationResponse result =
                organizationService.updateName(
                        ORGANIZATION_ID,
                        new UpdateOrganizationRequest(
                                " New   SafeAI ",
                                VERSION
                        ),
                        superAdminPrincipal()
                );

        assertThat(result.name())
                .isEqualTo(
                        "New SafeAI"
                );

        assertThat(result.version())
                .isEqualTo(
                        VERSION + 1L
                );

        verify(auditEventService).record(
                any(SafeAiUserPrincipal.class),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .ORGANIZATION_NAME_CHANGED
                ),
                argThat(
                        (Map<String, Object> details) ->
                                Long.valueOf(
                                        VERSION + 1L
                                ).equals(
                                        details.get(
                                                "version"
                                        )
                                )
                )
        );
    }

    @Test
    void updateNameRejectsStaleVersion() {
        OrganizationEntity entity =
                tenantOrganization();

        when(
                organizationRepository
                        .findByIdForSecurityUpdate(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        assertThatThrownBy(() ->
                organizationService.updateName(
                        ORGANIZATION_ID,
                        new UpdateOrganizationRequest(
                                "New Name",
                                VERSION - 1L
                        ),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(
                        OrganizationVersionConflictException.class
                )
                .satisfies(exception -> {
                    OrganizationVersionConflictException conflict =
                            (OrganizationVersionConflictException)
                                    exception;

                    assertThat(
                            conflict.getExpectedVersion()
                    ).isEqualTo(
                            VERSION - 1L
                    );

                    assertThat(
                            conflict.getActualVersion()
                    ).isEqualTo(
                            VERSION
                    );
                });

        verify(
                organizationRepository,
                never()
        ).saveAndFlush(any());
    }

    @Test
    void updateNameNoOpDoesNotIncrementVersion() {
        OrganizationEntity entity =
                tenantOrganization();

        when(
                organizationRepository
                        .findByIdForSecurityUpdate(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        OrganizationResponse result =
                organizationService.updateName(
                        ORGANIZATION_ID,
                        new UpdateOrganizationRequest(
                                "SafeAI",
                                VERSION
                        ),
                        superAdminPrincipal()
                );

        assertThat(result.version())
                .isEqualTo(VERSION);

        verify(
                organizationRepository,
                never()
        ).saveAndFlush(any());

        verifyNoInteractions(
                auditEventService
        );
    }

    @Test
    void platformOrganizationCannotBeMutated() {
        assertThatThrownBy(() ->
                organizationService.updateName(
                        PLATFORM_ORGANIZATION_ID,
                        new UpdateOrganizationRequest(
                                "Renamed",
                                0L
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
    void disableImpactReturnsVersionAndCounts() {
        OrganizationEntity entity =
                tenantOrganization();

        when(
                organizationRepository.findById(
                        ORGANIZATION_ID
                )
        ).thenReturn(
                Optional.of(entity)
        );

        when(
                impactQueryRepository.load(
                        ORGANIZATION_ID
                )
        ).thenReturn(
                new OrganizationImpactQueryRepository
                        .ImpactSnapshot(
                        10L,
                        2L,
                        4L,
                        1L
                )
        );

        OrganizationDisableImpactResponse result =
                organizationService.getDisableImpact(
                        ORGANIZATION_ID,
                        superAdminPrincipal()
                );

        assertThat(
                result.organizationVersion()
        ).isEqualTo(VERSION);

        assertThat(result.enabledUsers())
                .isEqualTo(10L);

        assertThat(
                result.activeChatOperations()
        ).isEqualTo(1L);
    }

    @Test
    void disableRejectsConfirmationMismatch() {
        OrganizationEntity entity =
                tenantOrganization();

        when(
                organizationRepository
                        .findByIdForSecurityUpdate(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        assertThatThrownBy(() ->
                organizationService.disable(
                        ORGANIZATION_ID,
                        new DisableOrganizationRequest(
                                VERSION,
                                "Other Company"
                        ),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "не совпадает"
                );

        verify(
                organizationRepository,
                never()
        ).saveAndFlush(any());
    }

    @Test
    void disableIncrementsSecurityEpochRevokesSessionsAndVersion() {
        OrganizationEntity entity =
                tenantOrganization();

        stubUpdatePersistence(entity);

        when(
                organizationRepository
                        .findByIdForSecurityUpdate(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        OrganizationResponse result =
                organizationService.disable(
                        ORGANIZATION_ID,
                        new DisableOrganizationRequest(
                                VERSION,
                                "SafeAI"
                        ),
                        superAdminPrincipal()
                );

        assertThat(result.enabled())
                .isFalse();

        assertThat(result.version())
                .isEqualTo(
                        VERSION + 1L
                );

        assertThat(entity.getAuthVersion())
                .isEqualTo(
                        AUTH_VERSION + 1L
                );

        verify(
                userSessionRevocationService
        ).revokeAllForOrganization(
                ORGANIZATION_ID,
                RefreshTokenRevocationReason
                        .ORGANIZATION_DISABLED
        );

        verify(eventPublisher).publishEvent(
                new OrganizationSecurityStateChangedEvent(
                        ORGANIZATION_ID,
                        AUTH_VERSION + 1L
                )
        );

        verify(auditEventService).record(
                any(SafeAiUserPrincipal.class),
                eq(ORGANIZATION_ID),
                eq(
                        AuditEventType
                                .ORGANIZATION_ENABLED_CHANGED
                ),
                argThat(
                        (Map<String, Object> details) ->
                                Boolean.TRUE.equals(
                                        details.get(
                                                "sessionsRevoked"
                                        )
                                )
                                        && Long.valueOf(
                                        VERSION + 1L
                                ).equals(
                                        details.get(
                                                "version"
                                        )
                                )
                )
        );
    }

    @Test
    void enablePreservesSecurityEpochAndDoesNotRestoreSessions() {
        OrganizationEntity entity =
                tenantOrganization();

        entity.setEnabled(false);
        entity.setAuthVersion(
                AUTH_VERSION + 1L
        );

        stubUpdatePersistence(entity);

        when(
                organizationRepository
                        .findByIdForSecurityUpdate(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        OrganizationResponse result =
                organizationService.enable(
                        ORGANIZATION_ID,
                        new EnableOrganizationRequest(
                                VERSION
                        ),
                        superAdminPrincipal()
                );

        assertThat(result.enabled())
                .isTrue();

        assertThat(entity.getAuthVersion())
                .isEqualTo(
                        AUTH_VERSION + 1L
                );

        verify(
                userSessionRevocationService,
                never()
        ).revokeAllForOrganization(
                any(UUID.class),
                any(
                        RefreshTokenRevocationReason.class
                )
        );

        verify(eventPublisher).publishEvent(
                new OrganizationSecurityStateChangedEvent(
                        ORGANIZATION_ID,
                        AUTH_VERSION + 1L
                )
        );
    }

    @Test
    @SuppressWarnings("deprecation")
    void compatibilityEndpointStillRequiresExpectedVersion() {
        OrganizationEntity entity =
                tenantOrganization();

        when(
                organizationRepository
                        .findByIdForSecurityUpdate(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        assertThatThrownBy(() ->
                organizationService.updateEnabled(
                        ORGANIZATION_ID,
                        new UpdateOrganizationEnabledRequest(
                                false,
                                VERSION - 1L
                        ),
                        superAdminPrincipal()
                )
        ).isInstanceOf(
                OrganizationVersionConflictException.class
        );
    }

    @Test
    void unchangedEnabledStateHasNoSideEffects() {
        OrganizationEntity entity =
                tenantOrganization();

        when(
                organizationRepository
                        .findByIdForSecurityUpdate(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        OrganizationResponse result =
                organizationService.enable(
                        ORGANIZATION_ID,
                        new EnableOrganizationRequest(
                                VERSION
                        ),
                        superAdminPrincipal()
                );

        assertThat(result.version())
                .isEqualTo(VERSION);

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

    private void stubCreatePersistence() {
        when(
                organizationRepository.saveAndFlush(
                        any(OrganizationEntity.class)
                )
        ).thenAnswer(invocation -> {
            OrganizationEntity entity =
                    invocation.getArgument(0);

            entity.setId(
                    ORGANIZATION_ID
            );
            entity.setVersion(0L);

            return entity;
        });

        stubRefreshTimestamps();
    }

    private void stubUpdatePersistence(
            OrganizationEntity entity
    ) {
        when(
                organizationRepository.saveAndFlush(
                        same(entity)
                )
        ).thenAnswer(invocation -> {
            OrganizationEntity saved =
                    invocation.getArgument(0);

            saved.setVersion(
                    Math.addExact(
                            saved.getVersion(),
                            1L
                    )
            );

            return saved;
        });

        stubRefreshTimestamps();
    }

    private void stubRefreshTimestamps() {
        doAnswer(invocation -> {
            OrganizationEntity entity =
                    invocation.getArgument(0);

            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(
                        CREATED_AT
                );
            }

            entity.setUpdatedAt(
                    UPDATED_AT
            );

            return null;
        }).when(entityManager).refresh(
                any(OrganizationEntity.class)
        );
    }

    private OrganizationEntity tenantOrganization() {
        OrganizationEntity entity =
                new OrganizationEntity();

        entity.setId(
                ORGANIZATION_ID
        );
        entity.setName("SafeAI");
        entity.setEnabled(true);
        entity.setAuthVersion(
                AUTH_VERSION
        );
        entity.setVersion(VERSION);
        entity.setCreatedAt(
                CREATED_AT
        );
        entity.setUpdatedAt(
                UPDATED_AT
        );

        return entity;
    }

    private OrganizationEntity platformOrganization() {
        OrganizationEntity entity =
                new OrganizationEntity();

        entity.setId(
                PLATFORM_ORGANIZATION_ID
        );
        entity.setName(
                "SafeAI Platform"
        );
        entity.setEnabled(true);
        entity.setAuthVersion(0L);
        entity.setVersion(0L);
        entity.setCreatedAt(
                CREATED_AT
        );
        entity.setUpdatedAt(
                UPDATED_AT
        );

        return entity;
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        ADMIN_ID,
                        ORGANIZATION_ID,
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