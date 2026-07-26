package ru.safeai.gateway.organization.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.persistence.DatabaseConstraintClassifier;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.UpdateOrganizationEnabledRequest;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(PlatformProperties.class)
public class OrganizationService {

    private static final Set<String> ALLOWED_SORT_PROPERTIES =
            Set.of("createdAt", "name", "enabled", "updatedAt");

    private static final Set<String>
            ORGANIZATION_NAME_UNIQUE_CONSTRAINTS =
            Set.of(
                    "ux_organizations_name_normalized"
            );

    private final OrganizationRepository organizationRepository;
    private final AuditEventService auditEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformProperties platformProperties;
    private final UserSessionRevocationService userSessionRevocationService;
    private final EntityManager entityManager;

    @Transactional
    public OrganizationResponse create(
            CreateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        requireSuperAdmin(currentUser);

        String canonicalName =
                OrganizationNameNormalizer.canonicalize(request.name());
        String normalizedName =
                OrganizationNameNormalizer.normalize(canonicalName);

        if (organizationRepository.existsByNormalizedName(normalizedName)) {
            throw duplicateOrganizationName(canonicalName);
        }

        OrganizationEntity entity = new OrganizationEntity();
        entity.setName(canonicalName);
        entity.setEnabled(true);
        entity.setAuthVersion(0L);

        OrganizationEntity saved;
        try {
            saved = saveAndRefresh(entity);
        } catch (DataIntegrityViolationException exception) {
            if (isOrganizationNameUniqueViolation(exception)) {
                throw duplicateOrganizationName(canonicalName);
            }

            throw exception;
        }

        auditEventService.record(
                currentUser.getId(),
                saved.getId(),
                AuditEventType.ORGANIZATION_CREATED,
                Map.of(
                        "organizationId", saved.getId().toString(),
                        "organizationName", saved.getName(),
                        "authVersion", saved.getAuthVersion()
                )
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<OrganizationResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        Objects.requireNonNull(pageable, "pageable не должен быть null");

        Pageable stablePageable = normalizePageable(pageable);

        if (isSuperAdmin(currentUser)) {
            return organizationRepository
                    .findAllStable(stablePageable)
                    .map(this::toResponse);
        }

        if (stablePageable.getOffset() > 0) {
            return new PageImpl<>(List.of(), stablePageable, 1);
        }

        OrganizationEntity organization = organizationRepository
                .findById(currentUser.getOrganizationId())
                .orElseThrow(() -> organizationNotFound(
                        currentUser.getOrganizationId()
                ));

        return new PageImpl<>(
                List.of(toResponse(organization)),
                stablePageable,
                1
        );
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findCurrentOrganization(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        return organizationRepository
                .findById(currentUser.getOrganizationId())
                .map(this::toResponse)
                .orElseThrow(() -> organizationNotFound(
                        currentUser.getOrganizationId()
                ));
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(id, "id не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        if (!isSuperAdmin(currentUser)
                && !currentUser.getOrganizationId().equals(id)) {
            throw organizationNotFound(id);
        }

        return organizationRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> organizationNotFound(id));
    }

    @Transactional
    public OrganizationResponse updateName(
            UUID id,
            UpdateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        OrganizationEntity entity =
                findMutableOrganizationForSuperAdmin(id, currentUser);

        String canonicalName =
                OrganizationNameNormalizer.canonicalize(request.name());
        String normalizedName =
                OrganizationNameNormalizer.normalize(canonicalName);

        if (entity.getName().equals(canonicalName)) {
            return toResponse(entity);
        }

        if (organizationRepository.existsByNormalizedNameAndIdNot(
                normalizedName,
                id
        )) {
            throw duplicateOrganizationName(canonicalName);
        }

        String oldName = entity.getName();
        entity.setName(canonicalName);

        OrganizationEntity saved;
        try {
            saved = saveAndRefresh(entity);
        } catch (DataIntegrityViolationException exception) {
            if (isOrganizationNameUniqueViolation(exception)) {
                throw duplicateOrganizationName(canonicalName);
            }

            throw exception;
        }

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
    }

    @Transactional
    public OrganizationResponse updateEnabled(
            UUID id,
            UpdateOrganizationEnabledRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        OrganizationEntity entity =
                findMutableOrganizationForSuperAdmin(id, currentUser);

        boolean oldEnabled = entity.isEnabled();
        boolean newEnabled = Boolean.TRUE.equals(request.enabled());

        if (oldEnabled == newEnabled) {
            return toResponse(entity);
        }

        long oldAuthVersion = entity.getAuthVersion();

        entity.setEnabled(newEnabled);

        if (!newEnabled) {
            entity.setAuthVersion(Math.addExact(oldAuthVersion, 1L));
        }

        OrganizationEntity saved = saveAndRefresh(entity);

        boolean sessionsRevoked = false;
        if (!newEnabled) {
            userSessionRevocationService.revokeAllForOrganization(
                    saved.getId(),
                    RefreshTokenRevocationReason.ORGANIZATION_DISABLED
            );
            sessionsRevoked = true;
        }

        eventPublisher.publishEvent(
                new OrganizationSecurityStateChangedEvent(
                        saved.getId(),
                        saved.getAuthVersion()
                )
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
                        "oldAuthVersion", oldAuthVersion,
                        "newAuthVersion", saved.getAuthVersion(),
                        "sessionsRevoked", sessionsRevoked,
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

        return organizationRepository.findByIdForSecurityUpdate(id)
                .orElseThrow(() -> organizationNotFound(id));
    }

    private OrganizationEntity saveAndRefresh(
            OrganizationEntity organization
    ) {
        OrganizationEntity saved =
                organizationRepository.saveAndFlush(organization);
        entityManager.refresh(saved);
        return saved;
    }

    private Pageable normalizePageable(Pageable pageable) {
        validatePageableSort(pageable);

        Sort sort = pageable.getSort().isUnsorted()
                ? Sort.by(Sort.Order.desc("createdAt"))
                : pageable.getSort();

        if (sort.stream().noneMatch(
                order -> "id".equals(order.getProperty())
        )) {
            sort = sort.and(Sort.by(Sort.Order.desc("id")));
        }

        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                sort
        );
    }

    private void validatePageableSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException(
                        "Сортировка по полю не разрешена: "
                                + order.getProperty()
                );
            }
        }
    }

    private OrganizationResponse toResponse(
            OrganizationEntity organization
    ) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.isEnabled(),
                organization.getCreatedAt(),
                organization.getUpdatedAt()
        );
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }

    private void requireSuperAdmin(SafeAiUserPrincipal currentUser) {
        if (!isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Только SUPER_ADMIN может управлять организациями"
            );
        }
    }

    private void rejectPlatformOrganizationMutation(UUID organizationId) {
        if (platformProperties.organizationId().equals(organizationId)) {
            throw new ForbiddenOperationException(
                    "Платформенную организацию нельзя изменять через "
                            + "обычный organization-management endpoint"
            );
        }
    }

    private boolean isOrganizationNameUniqueViolation(
            Throwable exception
    ) {
        return DatabaseConstraintClassifier.isUniqueViolation(
                exception,
                ORGANIZATION_NAME_UNIQUE_CONSTRAINTS
                        .toArray(String[]::new)
        );
    }

    private ConflictException duplicateOrganizationName(
            String name
    ) {
        return new ConflictException(
                "Организация с таким названием уже существует: "
                        + name
        );
    }

    private ResourceNotFoundException organizationNotFound(UUID id) {
        return new ResourceNotFoundException(
                "Организация не найдена: " + id
        );
    }
}