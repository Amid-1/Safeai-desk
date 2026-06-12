package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private OrganizationRepository organizationRepository;

    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationService = new OrganizationService(organizationRepository);
    }

    @Test
    void create_shouldCreateOrganization() {
        when(organizationRepository.existsByNameIgnoreCase("SafeAI"))
                .thenReturn(false);

        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrganizationResponse response = organizationService.create(
                new CreateOrganizationRequest(" SafeAI ")
        );

        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("SafeAI");
        assertThat(response.createdAt()).isNotNull();

        ArgumentCaptor<OrganizationEntity> captor =
                ArgumentCaptor.forClass(OrganizationEntity.class);

        verify(organizationRepository).save(captor.capture());

        OrganizationEntity savedEntity = captor.getValue();

        assertThat(savedEntity.getId()).isNotNull();
        assertThat(savedEntity.getName()).isEqualTo("SafeAI");
        assertThat(savedEntity.getCreatedAt()).isNotNull();
    }

    @Test
    void create_shouldThrowConflictWhenOrganizationNameAlreadyExists() {
        when(organizationRepository.existsByNameIgnoreCase("SafeAI"))
                .thenReturn(true);

        assertThatThrownBy(() -> organizationService.create(
                new CreateOrganizationRequest("SafeAI")
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Организация с таким названием уже существует");

        verify(organizationRepository, never()).save(any());
    }

    @Test
    void findAll_shouldReturnOrganizations() {
        OrganizationEntity organization = organizationEntity();

        when(organizationRepository.findAll())
                .thenReturn(List.of(organization));

        List<OrganizationResponse> response = organizationService.findAll();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().id()).isEqualTo(ORGANIZATION_ID);
        assertThat(response.getFirst().name()).isEqualTo("SafeAI");
    }

    @Test
    void findById_shouldReturnOrganization() {
        OrganizationEntity organization = organizationEntity();

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));

        OrganizationResponse response = organizationService.findById(ORGANIZATION_ID);

        assertThat(response.id()).isEqualTo(ORGANIZATION_ID);
        assertThat(response.name()).isEqualTo("SafeAI");
    }

    @Test
    void findById_shouldThrowResourceNotFoundWhenOrganizationDoesNotExist() {
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> organizationService.findById(ORGANIZATION_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Организация не найдена");
    }

    private OrganizationEntity organizationEntity() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("SafeAI");
        organization.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));

        return organization;
    }
}