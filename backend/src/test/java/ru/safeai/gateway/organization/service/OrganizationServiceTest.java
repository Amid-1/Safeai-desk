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

    private static final UUID ADMIN_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private OrganizationRepository organizationRepository;

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
                auditEventService,
                eventPublisher,
                new PlatformProperties(PLATFORM_ORGANIZATION_ID),
                userSessionRevocationService
        );
    }

    @Test
    void create_shouldCreateOrganization() {
        when(organizationRepository.existsByNameIgnoreCase("SafeAI"))
                .thenReturn(false);

        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(invocation -> {
                    OrganizationEntity entity = invocation.getArgument(0);

                    entity.setId(ORGANIZATION_ID);
                    entity.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));

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

        verify(organizationRepository).save(captor.capture());

        OrganizationEntity saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("SafeAI");
        assertThat(saved.isEnabled()).isTrue();

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.ORGANIZATION_CREATED),
                anyMap()
        );

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

        verify(organizationRepository, never()).save(any());
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void create_shouldThrowForbiddenForAdmin() {
        assertThatThrownBy(() ->
                organizationService.create(
                        new CreateOrganizationRequest("SafeAI"),
                        adminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Only SUPER_ADMIN");

        verifyNoInteractions(organizationRepository);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void findAll_shouldReturnCurrentOrganizationForAdmin() {
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization()));

        Page<OrganizationResponse> page = organizationService.findAll(
                adminPrincipal(),
                PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().id()).isEqualTo(ORGANIZATION_ID);
    }

    @Test
    void findAll_shouldReturnEmptyPageForAdminWhenPageOffsetIsNotZero() {
        Page<OrganizationResponse> page = organizationService.findAll(
                adminPrincipal(),
                PageRequest.of(1, 20)
        );

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findById_shouldReturnOrganization() {
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
    void findById_shouldThrowWhenNotFound() {
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
    void findById_shouldThrowWhenAdminReadsAnotherOrganization() {
        UUID anotherOrganizationId =
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

        assertThatThrownBy(() ->
                organizationService.findById(
                        anotherOrganizationId,
                        adminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Нельзя просматривать другую организацию");

        verifyNoInteractions(organizationRepository);
    }

    @Test
    void updateName_shouldUpdateOrganizationName() {
        OrganizationEntity organization = organization();

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));

        when(organizationRepository.existsByNameIgnoreCaseAndIdNot("New SafeAI", ORGANIZATION_ID))
                .thenReturn(false);

        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrganizationResponse response = organizationService.updateName(
                ORGANIZATION_ID,
                new UpdateOrganizationRequest(" New   SafeAI "),
                superAdminPrincipal()
        );

        assertThat(response.name()).isEqualTo("New SafeAI");
        assertThat(organization.getName()).isEqualTo("New SafeAI");

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.ORGANIZATION_NAME_CHANGED),
                anyMap()
        );

        verifyNoInteractions(userSessionRevocationService);
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

        verify(organizationRepository, never()).save(any());
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
    void updateEnabled_shouldDisableOrganizationAndRevokeRefreshSessions() {
        OrganizationEntity organization = organization();

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));

        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrganizationResponse response = organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(false),
                superAdminPrincipal()
        );

        assertThat(response.enabled()).isFalse();
        assertThat(organization.isEnabled()).isFalse();

        verify(userSessionRevocationService).revokeAllForOrganization(ORGANIZATION_ID);
        verify(eventPublisher).publishEvent(any(OrganizationSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.ORGANIZATION_ENABLED_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateEnabled_shouldEnableOrganizationWithoutRestoringRefreshSessions() {
        OrganizationEntity organization = organization();
        organization.setEnabled(false);

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));

        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrganizationResponse response = organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(true),
                superAdminPrincipal()
        );

        assertThat(response.enabled()).isTrue();
        assertThat(organization.isEnabled()).isTrue();

        verify(userSessionRevocationService, never()).revokeAllForOrganization(any());
        verify(eventPublisher).publishEvent(any(OrganizationSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(ADMIN_ID),
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

        verify(organizationRepository, never()).save(any());
        verifyNoInteractions(userSessionRevocationService);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(auditEventService);
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
                ADMIN_ID,
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