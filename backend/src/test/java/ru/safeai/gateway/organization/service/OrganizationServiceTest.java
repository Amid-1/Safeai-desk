package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.time.LocalDateTime;
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

    @InjectMocks
    private OrganizationService organizationService;

    @Test
    void create_shouldCreateOrganization() {
        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OrganizationResponse response = organizationService.create(
                new CreateOrganizationRequest("Test Company")
        );

        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("Test Company");
        assertThat(response.createdAt()).isNotNull();

        ArgumentCaptor<OrganizationEntity> entityCaptor =
                ArgumentCaptor.forClass(OrganizationEntity.class);

        verify(organizationRepository).save(entityCaptor.capture());

        OrganizationEntity savedEntity = entityCaptor.getValue();

        assertThat(savedEntity.getId()).isNotNull();
        assertThat(savedEntity.getName()).isEqualTo("Test Company");
        assertThat(savedEntity.getCreatedAt()).isNotNull();
    }

    @Test
    void findAll_shouldReturnOrganizations() {
        OrganizationEntity first = organizationEntity(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "First Company"
        );

        OrganizationEntity second = organizationEntity(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Second Company"
        );

        when(organizationRepository.findAll())
                .thenReturn(List.of(first, second));

        List<OrganizationResponse> responses = organizationService.findAll();

        assertThat(responses).hasSize(2);

        assertThat(responses.get(0).id()).isEqualTo(first.getId());
        assertThat(responses.get(0).name()).isEqualTo("First Company");

        assertThat(responses.get(1).id()).isEqualTo(second.getId());
        assertThat(responses.get(1).name()).isEqualTo("Second Company");

        verify(organizationRepository).findAll();
    }

    @Test
    void findById_shouldReturnOrganizationWhenExists() {
        OrganizationEntity entity = organizationEntity(
                ORGANIZATION_ID,
                "Demo Company"
        );

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(entity));

        OrganizationResponse response = organizationService.findById(ORGANIZATION_ID);

        assertThat(response.id()).isEqualTo(ORGANIZATION_ID);
        assertThat(response.name()).isEqualTo("Demo Company");
        assertThat(response.createdAt()).isEqualTo(entity.getCreatedAt());

        verify(organizationRepository).findById(ORGANIZATION_ID);
    }

    @Test
    void findById_shouldThrowResourceNotFoundWhenOrganizationDoesNotExist() {
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> organizationService.findById(ORGANIZATION_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Организация не найдена");

        verify(organizationRepository).findById(ORGANIZATION_ID);
    }

    private OrganizationEntity organizationEntity(UUID id, String name) {
        OrganizationEntity entity = new OrganizationEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setCreatedAt(LocalDateTime.now());

        return entity;
    }
}