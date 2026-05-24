package ru.safeai.gateway.organization.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    @Transactional
    public OrganizationResponse create(CreateOrganizationRequest request) {
        OrganizationEntity entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(request.name());
        entity.setCreatedAt(LocalDateTime.now());

        OrganizationEntity saved = organizationRepository.save(entity);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> findAll() {
        return organizationRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findById(UUID id) {
        OrganizationEntity entity = organizationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + id));

        return toResponse(entity);
    }

    private OrganizationResponse toResponse(OrganizationEntity entity) {
        return new OrganizationResponse(
                entity.getId(),
                entity.getName(),
                entity.getCreatedAt()
        );
    }
}