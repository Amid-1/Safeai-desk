package ru.safeai.gateway.organization.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        String name = normalizeName(request.name());

        if (organizationRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("Организация с таким названием уже существует: " + name);
        }

        try {
            OrganizationEntity entity = new OrganizationEntity();
            entity.setName(name);

            OrganizationEntity saved = organizationRepository.save(entity);

            auditEventService.record(
                    currentUser.getId(),
                    saved.getId(),
                    AuditEventType.ORGANIZATION_CREATED,
                    Map.of(
                            "organizationId", saved.getId().toString(),
                            "organizationName", saved.getName()
                    )
            );

            return toResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Организация с таким названием уже существует: " + name);
        }
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> findAll(SafeAiUserPrincipal currentUser) {
        if (isSuperAdmin(currentUser)) {
            return organizationRepository.findAllByOrderByCreatedAtDesc()
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        OrganizationEntity organization = organizationRepository.findById(currentUser.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + currentUser.getOrganizationId()
                ));

        return List.of(toResponse(organization));
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findById(UUID id, SafeAiUserPrincipal currentUser) {
        if (!isSuperAdmin(currentUser) && !currentUser.getOrganizationId().equals(id)) {
            throw new ForbiddenOperationException("Нельзя просматривать другую организацию");
        }

        OrganizationEntity entity = organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + id
                ));

        return toResponse(entity);
    }

    private String normalizeName(String name) {
        return name.trim().replaceAll("\\s+", " ");
    }

    private OrganizationResponse toResponse(OrganizationEntity entity) {
        return new OrganizationResponse(
                entity.getId(),
                entity.getName(),
                entity.getCreatedAt()
        );
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }
}