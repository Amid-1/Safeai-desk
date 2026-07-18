package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
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
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private static final UUID ADMIN_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final UUID SAVED_ORGANIZATION_ID =
            UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private static final Instant SAVED_AT =
            Instant.parse("2026-06-12T12:00:00Z");

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditEventService auditEventService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserSessionRevocationService userSessionRevocationService;

    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationService(
                organizationRepository,
                userRepository,
                auditEventService,
                eventPublisher,
                new PlatformProperties(PLATFORM_ORGANIZATION_ID),
                userSessionRevocationService
        );
    }

    @Test
    void create_shouldCreateOrganizationWhenCurrentUserIsSuperAdmin() {
        when(organizationRepository.existsByNameIgnoreCase("SafeAI"))
                .thenReturn(false);

        when(organizationRepository.saveAndFlush(
                any(OrganizationEntity.class)
        )).thenAnswer(invocation -> {
            OrganizationEntity entity = invocation.getArgument(0);
            entity.setId(ORGANIZATION_ID);
            entity.setCreatedAt(SAVED_AT);
            return entity;
        });

        OrganizationResponse response = organizationService.create(
                new CreateOrganizationRequest(" SafeAI "),
                superAdminPrincipal()
        );

        assertThat(response.id()).isEqualTo(ORGANIZATION_ID);
        assertThat(response.name()).isEqualTo("SafeAI");
        assertThat(response.enabled()).isTrue();

        ArgumentCaptor<OrganizationEntity> captor =
                ArgumentCaptor.forClass(OrganizationEntity.class);

        verify(organizationRepository).saveAndFlush(captor.capture());

        OrganizationEntity saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("SafeAI");
        assertThat(saved.isEnabled()).isTrue();

        verify(auditEventService).record(
                eq(SUPER_ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.ORGANIZATION_CREATED),
                anyMap()
        );

        verifyNoInteractions(userSessionRevocationService);
        verifyNoInteractions(userRepository);
    }

    @Test
    void create_shouldThrowForbiddenWhenCurrentUserIsAdmin() {
        assertThatThrownBy(() ->
                organizationService.create(
                        new CreateOrganizationRequest("SafeAI"),
                        adminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Only SUPER_ADMIN");

        verifyNoInteractions(organizationRepository);
        verifyNoInteractions(userRepository);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void create_shouldThrowConflictWhenNameAlreadyExists() {
        when(organizationRepository.existsByNameIgnoreCase("SafeAI"))
                .thenReturn(true);

        assertThatThrownBy(() ->
                organizationService.create(
                        new CreateOrganizationRequest("SafeAI"),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Организация с таким названием уже существует");

        verify(organizationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userRepository);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void create_shouldThrowConflictWhenNameAlreadyExistsWithDifferentCase() {
        when(organizationRepository.existsByNameIgnoreCase("safeai"))
                .thenReturn(true);

        assertThatThrownBy(() ->
                organizationService.create(
                        new CreateOrganizationRequest("safeai"),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Организация с таким названием уже существует");

        verify(organizationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void findAll_shouldReturnCurrentOrganizationOnlyForAdmin() {
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization()));

        Page<OrganizationResponse> page = organizationService.findAll(
                adminPrincipal(),
                PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().id()).isEqualTo(ORGANIZATION_ID);

        verify(organizationRepository).findById(ORGANIZATION_ID);
    }

    @Test
    void findAll_shouldReturnEmptyPageForAdminWhenPageOffsetIsNotZero() {
        Page<OrganizationResponse> page = organizationService.findAll(
                adminPrincipal(),
                PageRequest.of(1, 20)
        );

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);

        verifyNoInteractions(organizationRepository);
    }

    @Test
    void findAll_shouldReturnAllOrganizationsForSuperAdmin() {
        when(organizationRepository.findAll(any(PageRequest.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(organization()),
                        PageRequest.of(0, 20),
                        1
                ));

        Page<OrganizationResponse> page = organizationService.findAll(
                superAdminPrincipal(),
                PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().id()).isEqualTo(ORGANIZATION_ID);
    }

    @Test
    void findCurrentOrganization_shouldReturnCurrentOrganization() {
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization()));

        OrganizationResponse response = organizationService.findCurrentOrganization(
                adminPrincipal()
        );

        assertThat(response.id()).isEqualTo(ORGANIZATION_ID);
        assertThat(response.name()).isEqualTo("SafeAI");
    }

    @Test
    void findById_shouldReturnOrganizationWhenAdminReadsOwnOrganization() {
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization()));

        OrganizationResponse response =
                organizationService.findById(
                        ORGANIZATION_ID,
                        adminPrincipal()
                );

        assertThat(response.id()).isEqualTo(ORGANIZATION_ID);
        assertThat(response.name()).isEqualTo("SafeAI");
    }

    @Test
    void findById_shouldThrowWhenOrganizationNotFound() {
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                organizationService.findById(
                        ORGANIZATION_ID,
                        adminPrincipal()
                )
        )
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findById_shouldThrowForbiddenWhenAdminReadsAnotherOrganization() {
        assertThatThrownBy(() ->
                organizationService.findById(
                        OTHER_ORGANIZATION_ID,
                        adminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Нельзя просматривать другую организацию");

        verifyNoInteractions(organizationRepository);
    }

    @Test
    void updateName_shouldUpdateNormalOrganizationWhenCurrentUserIsSuperAdmin() {
        OrganizationEntity organization = organization();

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));

        when(organizationRepository.existsByNameIgnoreCaseAndIdNot("New SafeAI", ORGANIZATION_ID))
                .thenReturn(false);

        stubOrganizationSaveAndFlush();

        OrganizationResponse response = organizationService.updateName(
                ORGANIZATION_ID,
                new UpdateOrganizationRequest(" New   SafeAI "),
                superAdminPrincipal()
        );

        assertThat(response.name()).isEqualTo("New SafeAI");
        assertThat(organization.getName()).isEqualTo("New SafeAI");

        verify(auditEventService).record(
                eq(SUPER_ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.ORGANIZATION_NAME_CHANGED),
                anyMap()
        );

        verifyNoInteractions(userSessionRevocationService);
        verifyNoInteractions(userRepository);
    }

    @Test
    void updateName_shouldThrowConflictWhenNameAlreadyExists() {
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization()));

        when(organizationRepository.existsByNameIgnoreCaseAndIdNot("Other", ORGANIZATION_ID))
                .thenReturn(true);

        assertThatThrownBy(() ->
                organizationService.updateName(
                        ORGANIZATION_ID,
                        new UpdateOrganizationRequest("Other"),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Организация с таким названием уже существует");

        verify(organizationRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateName_shouldThrowForbiddenWhenCurrentUserIsAdmin() {
        assertThatThrownBy(() ->
                organizationService.updateName(
                        ORGANIZATION_ID,
                        new UpdateOrganizationRequest("Other"),
                        adminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Only SUPER_ADMIN");

        verifyNoInteractions(organizationRepository);
    }

    @Test
    void updateName_shouldThrowForbiddenForPlatformOrganization() {
        assertThatThrownBy(() ->
                organizationService.updateName(
                        PLATFORM_ORGANIZATION_ID,
                        new UpdateOrganizationRequest("Platform New Name"),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Платформенную организацию нельзя изменять");

        verifyNoInteractions(organizationRepository);
    }

    @Test
    void updateEnabled_shouldDisableNormalOrganizationAndRevokeRefreshSessionsAndIncrementUserTokenVersions() {
        OrganizationEntity organization = organization();

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));

        stubOrganizationSaveAndFlush();

        when(userRepository.incrementTokenVersionByOrganizationId(ORGANIZATION_ID))
                .thenReturn(5);

        OrganizationResponse response = organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(false),
                superAdminPrincipal()
        );

        assertThat(response.enabled()).isFalse();
        assertThat(organization.isEnabled()).isFalse();

        verify(userSessionRevocationService).revokeAllForOrganization(ORGANIZATION_ID);
        verify(userRepository).incrementTokenVersionByOrganizationId(ORGANIZATION_ID);
        verify(eventPublisher).publishEvent(any(OrganizationSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(SUPER_ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.ORGANIZATION_ENABLED_CHANGED),
                argThat(details ->
                        details.containsKey("affectedUsers")
                                && details.containsKey("requiresRelogin")
                )
        );
    }

    @Test
    void updateEnabled_shouldEnableOrganizationWithoutRestoringRefreshSessionsOrIncrementingTokenVersions() {
        OrganizationEntity organization = organization();
        organization.setEnabled(false);

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));

        stubOrganizationSaveAndFlush();

        OrganizationResponse response = organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(true),
                superAdminPrincipal()
        );

        assertThat(response.enabled()).isTrue();
        assertThat(organization.isEnabled()).isTrue();

        verify(userSessionRevocationService, never()).revokeAllForOrganization(any());
        verify(userRepository, never()).incrementTokenVersionByOrganizationId(any());
        verify(eventPublisher).publishEvent(any(OrganizationSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(SUPER_ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.ORGANIZATION_ENABLED_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateEnabled_shouldReturnWithoutChangesWhenValueIsSame() {
        OrganizationEntity organization = organization();

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));

        OrganizationResponse response = organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(true),
                superAdminPrincipal()
        );

        assertThat(response.enabled()).isTrue();

        verify(organizationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userSessionRevocationService);
        verifyNoInteractions(userRepository);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(auditEventService);
    }

    @Test
    void updateEnabled_shouldThrowForbiddenWhenCurrentUserIsAdmin() {
        assertThatThrownBy(() ->
                organizationService.updateEnabled(
                        ORGANIZATION_ID,
                        new UpdateOrganizationEnabledRequest(false),
                        adminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Only SUPER_ADMIN");

        verifyNoInteractions(organizationRepository);
        verifyNoInteractions(userSessionRevocationService);
        verifyNoInteractions(userRepository);
    }

    @Test
    void updateEnabled_shouldThrowForbiddenForPlatformOrganization() {
        assertThatThrownBy(() ->
                organizationService.updateEnabled(
                        PLATFORM_ORGANIZATION_ID,
                        new UpdateOrganizationEnabledRequest(false),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Платформенную организацию нельзя изменять");

        verifyNoInteractions(organizationRepository);
        verifyNoInteractions(userSessionRevocationService);
        verifyNoInteractions(userRepository);
    }


    private void stubOrganizationSaveAndFlush() {
        when(organizationRepository.saveAndFlush(
                any(OrganizationEntity.class)
        )).thenAnswer(invocation -> {
            OrganizationEntity entity = invocation.getArgument(0);

            if (entity.getId() == null) {
                entity.setId(SAVED_ORGANIZATION_ID);
            }

            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(SAVED_AT);
            }

            return entity;
        });
    }

    private OrganizationEntity organization() {
        OrganizationEntity entity = new OrganizationEntity();
        entity.setId(ORGANIZATION_ID);
        entity.setName("SafeAI");
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        return entity;
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return new SafeAiUserPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "",
                true,
                0L,
                List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN")
                )
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
                List.of(
                        new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
                )
        );
    }
}