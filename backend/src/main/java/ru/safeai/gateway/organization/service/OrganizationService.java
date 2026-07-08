package ru.safeai.gateway.organization.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.BadRequestException;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(PlatformProperties.class)
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final AuditEventService auditEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformProperties platformProperties;
    private final UserSessionRevocationService userSessionRevocationService;

    @Transactional
    public OrganizationResponse create(
            CreateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        requireSuperAdmin(currentUser);

        String name = normalizeName(request.name());

        if (organizationRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("Организация с таким названием уже существует: " + name);
        }

        try {
            OrganizationEntity entity = new OrganizationEntity();
            entity.setName(name);
            entity.setEnabled(true);

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
    public Page<OrganizationResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        Objects.requireNonNull(pageable, "pageable не должен быть null");

        if (isSuperAdmin(currentUser)) {
            return organizationRepository.findAll(pageable)
                    .map(this::toResponse);
        }

        if (pageable.getOffset() > 0) {
            return new PageImpl<>(List.of(), pageable, 1);
        }

        OrganizationEntity organization = organizationRepository
                .findById(currentUser.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + currentUser.getOrganizationId()
                ));

        return new PageImpl<>(
                List.of(toResponse(organization)),
                pageable,
                1
        );
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findCurrentOrganization(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        OrganizationEntity organization = organizationRepository
                .findById(currentUser.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + currentUser.getOrganizationId()
                ));

        return toResponse(organization);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(id, "id не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        if (!isSuperAdmin(currentUser) && !currentUser.getOrganizationId().equals(id)) {
            throw new ForbiddenOperationException("Нельзя просматривать другую организацию");
        }

        OrganizationEntity entity = organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + id
                ));

        return toResponse(entity);
    }

    @Transactional
    public OrganizationResponse updateName(
            UUID id,
            UpdateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        OrganizationEntity entity = findMutableOrganizationForSuperAdmin(id, currentUser);

        String oldName = entity.getName();
        String newName = normalizeName(request.name());

        if (oldName.equals(newName)) {
            return toResponse(entity);
        }

        if (organizationRepository.existsByNameIgnoreCaseAndIdNot(newName, id)) {
            throw new ConflictException("Организация с таким названием уже существует: " + newName);
        }

        try {
            entity.setName(newName);

            OrganizationEntity saved = organizationRepository.save(entity);

            auditEventService.record(
                    currentUser.getId(),
                    saved.getId(),
                    AuditEventType.ORGANIZATION_NAME_CHANGED,
                    Map.of(
                            "organizationId", saved.getId().toString(),
                            "oldName", oldName,
                            "newName", saved.getName()
                    )
            );

            return toResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Организация с таким названием уже существует: " + newName);
        }
    }

    @Transactional
    public OrganizationResponse updateEnabled(
            UUID id,
            UpdateOrganizationEnabledRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        OrganizationEntity entity = findMutableOrganizationForSuperAdmin(id, currentUser);

        boolean oldEnabled = entity.isEnabled();
        boolean newEnabled = Boolean.TRUE.equals(request.enabled());

        if (oldEnabled == newEnabled) {
            return toResponse(entity);
        }

        entity.setEnabled(newEnabled);

        OrganizationEntity saved = organizationRepository.save(entity);

        boolean sessionsRevoked = false;
        int affectedUsers = 0;

        if (!newEnabled) {
            userSessionRevocationService.revokeAllForOrganization(saved.getId());
            affectedUsers = userRepository.incrementTokenVersionByOrganizationId(saved.getId());

            sessionsRevoked = true;
        }

        eventPublisher.publishEvent(
                new OrganizationSecurityStateChangedEvent(saved.getId())
        );

        auditEventService.record(
                currentUser.getId(),
                saved.getId(),
                AuditEventType.ORGANIZATION_ENABLED_CHANGED,
                Map.of(
                        "organizationId", saved.getId().toString(),
                        "organizationName", saved.getName(),
                        "oldEnabled", oldEnabled,
                        "newEnabled", saved.isEnabled(),
                        "sessionsRevoked", sessionsRevoked,
                        "affectedUsers", affectedUsers,
                        "requiresRelogin", !newEnabled
                )
        );

        return toResponse(saved);
    }

    private OrganizationEntity findMutableOrganizationForSuperAdmin(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(id, "id не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        requireSuperAdmin(currentUser);
        rejectPlatformOrganizationMutation(id);

        return organizationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + id
                ));
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Название организации не должно быть пустым");
        }

        return name.trim().replaceAll("\\s+", " ");
    }

    private OrganizationResponse toResponse(OrganizationEntity entity) {
        return new OrganizationResponse(
                entity.getId(),
                entity.getName(),
                entity.isEnabled(),
                entity.getCreatedAt()
        );
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }

    private void rejectPlatformOrganizationMutation(UUID organizationId) {
        if (platformProperties.effectiveOrganizationId().equals(organizationId)) {
            throw new ForbiddenOperationException(
                    "Платформенную организацию нельзя изменять через обычный organization-management endpoint"
            );
        }
    }

    private void requireSuperAdmin(SafeAiUserPrincipal currentUser) {
        if (!isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Only SUPER_ADMIN may manage organizations"
            );
        }
    }
}