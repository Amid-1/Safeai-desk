package ru.safeai.gateway.organization.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final AuditEventService auditEventService;

    @Transactional
    public OrganizationResponse create(
            CreateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        String name = request.name().trim();

        if (organizationRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("Организация с таким названием уже существует: " + name);
        }

        OrganizationEntity entity = new OrganizationEntity();
        entity.setName(name);

        OrganizationEntity saved = organizationRepository.save(entity);

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.ORGANIZATION_CREATED,
                Map.of(
                        "organizationId", saved.getId().toString(),
                        "organizationName", saved.getName()
                )
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> findAll() {
        return organizationRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findById(UUID id) {
        OrganizationEntity entity = organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + id
                ));

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