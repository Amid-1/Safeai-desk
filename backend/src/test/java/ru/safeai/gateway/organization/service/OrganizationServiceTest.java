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
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
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

    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationService(
                organizationRepository,
                auditEventService,
                eventPublisher,
                new PlatformProperties(PLATFORM_ORGANIZATION_ID)
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