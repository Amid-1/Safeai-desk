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
import ru.safeai.gateway.common.exception.OrganizationVersionConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.persistence.DatabaseConstraintClassifier;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.DisableOrganizationRequest;
import ru.safeai.gateway.organization.dto.EnableOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationDirectoryResponse;
import ru.safeai.gateway.organization.dto.OrganizationDisableImpactResponse;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.OrganizationType;
import ru.safeai.gateway.organization.dto.UpdateOrganizationEnabledRequest;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.organization.repository.OrganizationImpactQueryRepository;
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

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_DIRECTORY_LIMIT = 50;

    private static final Set<String>
            ALLOWED_SORT_PROPERTIES =
            Set.of(
                    "createdAt",
                    "name",
                    "enabled",
                    "updatedAt"
            );

    private static final Set<String>
            ORGANIZATION_NAME_UNIQUE_CONSTRAINTS =
            Set.of(
                    "ux_organizations_name_normalized"
            );

    private final OrganizationRepository
            organizationRepository;

    private final OrganizationImpactQueryRepository
            impactQueryRepository;

    private final AuditEventService
            auditEventService;

    private final ApplicationEventPublisher
            eventPublisher;

    private final PlatformProperties
            platformProperties;

    private final UserSessionRevocationService
            userSessionRevocationService;

    private final EntityManager
            entityManager;

    @Transactional
    public OrganizationResponse create(
            CreateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );
        requireSuperAdmin(currentUser);

        String canonicalName =
                OrganizationNameNormalizer.canonicalize(
                        request.name()
                );

        String normalizedName =
                OrganizationNameNormalizer.normalize(
                        canonicalName
                );

        if (
                organizationRepository
                        .existsByNormalizedName(
                                normalizedName
                        )
        ) {
            throw duplicateOrganizationName(
                    canonicalName
            );
        }

        OrganizationEntity entity =
                new OrganizationEntity();

        entity.setName(canonicalName);
        entity.setEnabled(true);
        entity.setAuthVersion(0L);

        OrganizationEntity saved;

        try {
            saved = saveAndRefresh(entity);
        } catch (
                DataIntegrityViolationException exception
        ) {
            if (
                    isOrganizationNameUniqueViolation(
                            exception
                    )
            ) {
                throw duplicateOrganizationName(
                        canonicalName
                );
            }

            throw exception;
        }

        auditEventService.record(
                currentUser,
                saved.getId(),
                AuditEventType.ORGANIZATION_CREATED,
                Map.of(
                        "organizationId",
                        saved.getId().toString(),
                        "organizationName",
                        saved.getName(),
                        "authVersion",
                        saved.getAuthVersion(),
                        "version",
                        saved.getVersion()
                )
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<OrganizationResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
        Objects.requireNonNull(
                pageable,
                "pageable не должен быть null"
        );

        Pageable stablePageable =
                normalizePageable(pageable);

        if (isSuperAdmin(currentUser)) {
            return organizationRepository
                    .findAllStable(stablePageable)
                    .map(this::toResponse);
        }

        if (stablePageable.getOffset() > 0L) {
            return new PageImpl<>(
                    List.of(),
                    stablePageable,
                    1L
            );
        }

        OrganizationEntity organization =
                organizationRepository
                        .findById(
                                currentUser
                                        .getOrganizationId()
                        )
                        .orElseThrow(() ->
                                organizationNotFound(
                                        currentUser
                                                .getOrganizationId()
                                )
                        );

        return new PageImpl<>(
                List.of(
                        toResponse(organization)
                ),
                stablePageable,
                1L
        );
    }

    @Transactional(readOnly = true)
    public List<OrganizationDirectoryResponse>
    findDirectory(
            String query,
            int limit,
            SafeAiUserPrincipal currentUser
    ) {
        requireSuperAdmin(currentUser);

        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();

        if (normalizedQuery.length() > 255) {
            throw new BadRequestException(
                    "Поисковый запрос не должен превышать "
                            + "255 символов"
            );
        }

        int safeLimit = Math.clamp(
                limit,
                1,
                MAX_DIRECTORY_LIMIT
        );

        Pageable pageable = PageRequest.of(
                0,
                safeLimit,
                Sort.by(
                        Sort.Order.asc("name"),
                        Sort.Order.asc("id")
                )
        );

        UUID exactId =
                parseUuidOrNull(
                        normalizedQuery
                );

        if (exactId != null) {
            return organizationRepository
                    .findById(exactId)
                    .map(
                            this::toDirectoryResponse
                    )
                    .map(List::of)
                    .orElseGet(List::of);
        }

        Page<OrganizationEntity> page =
                normalizedQuery.isEmpty()
                        ? organizationRepository
                        .findAll(pageable)
                        : organizationRepository
                        .searchDirectoryByName(
                                normalizedQuery,
                                pageable
                        );

        return page.getContent()
                .stream()
                .map(
                        this::toDirectoryResponse
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse
    findCurrentOrganization(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        UUID organizationId =
                currentUser.getOrganizationId();

        return organizationRepository
                .findById(organizationId)
                .map(this::toResponse)
                .orElseThrow(() ->
                        organizationNotFound(
                                organizationId
                        )
                );
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                id,
                "id не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (
                !isSuperAdmin(currentUser)
                        && !currentUser
                        .getOrganizationId()
                        .equals(id)
        ) {
            /*
             * 404 вместо 403 не раскрывает существование
             * чужой tenant organization.
             */
            throw organizationNotFound(id);
        }

        return organizationRepository
                .findById(id)
                .map(this::toResponse)
                .orElseThrow(() ->
                        organizationNotFound(id)
                );
    }

    @Transactional(readOnly = true)
    public OrganizationDisableImpactResponse
    getDisableImpact(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        OrganizationEntity entity =
                findMutableOrganizationSnapshot(
                        id,
                        currentUser
                );

        OrganizationImpactQueryRepository
                .ImpactSnapshot impact =
                impactQueryRepository.load(id);

        return new OrganizationDisableImpactResponse(
                entity.getId(),
                entity.getVersion(),
                impact.enabledUsers(),
                impact.administrators(),
                impact.activeRefreshSessions(),
                impact.activeChatOperations()
        );
    }

    @Transactional
    public OrganizationResponse updateName(
            UUID id,
            UpdateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        OrganizationEntity entity =
                findMutableOrganizationForUpdate(
                        id,
                        currentUser
                );

        requireExpectedVersion(
                entity,
                request.expectedVersion()
        );

        String canonicalName =
                OrganizationNameNormalizer.canonicalize(
                        request.name()
                );

        if (
                entity.getName()
                        .equals(canonicalName)
        ) {
            return toResponse(entity);
        }

        String normalizedName =
                OrganizationNameNormalizer.normalize(
                        canonicalName
                );

        if (
                organizationRepository
                        .existsByNormalizedNameAndIdNot(
                                normalizedName,
                                id
                        )
        ) {
            throw duplicateOrganizationName(
                    canonicalName
            );
        }

        String oldName =
                entity.getName();

        entity.setName(canonicalName);

        OrganizationEntity saved;

        try {
            saved = saveAndRefresh(entity);
        } catch (
                DataIntegrityViolationException exception
        ) {
            if (
                    isOrganizationNameUniqueViolation(
                            exception
                    )
            ) {
                throw duplicateOrganizationName(
                        canonicalName
                );
            }

            throw exception;
        }

        auditEventService.record(
                currentUser,
                saved.getId(),
                AuditEventType.ORGANIZATION_NAME_CHANGED,
                Map.of(
                        "organizationId",
                        saved.getId().toString(),
                        "oldName",
                        oldName,
                        "newName",
                        saved.getName(),
                        "version",
                        saved.getVersion()
                )
        );

        return toResponse(saved);
    }

    /**
     * Совместимость со старым клиентом.
     *
     * @deprecated Новые клиенты должны использовать
     * {@link #disable(UUID, DisableOrganizationRequest, SafeAiUserPrincipal)}
     * или
     * {@link #enable(UUID, EnableOrganizationRequest, SafeAiUserPrincipal)}.
     */
    @Deprecated
    @Transactional
    public OrganizationResponse updateEnabled(
            UUID id,
            UpdateOrganizationEnabledRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        OrganizationEntity entity =
                findMutableOrganizationForUpdate(
                        id,
                        currentUser
                );

        requireExpectedVersion(
                entity,
                request.expectedVersion()
        );

        return changeEnabled(
                entity,
                Boolean.TRUE.equals(
                        request.enabled()
                ),
                currentUser
        );
    }

    @Transactional
    public OrganizationResponse disable(
            UUID id,
            DisableOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        OrganizationEntity entity =
                findMutableOrganizationForUpdate(
                        id,
                        currentUser
                );

        requireExpectedVersion(
                entity,
                request.expectedVersion()
        );

        String expectedConfirmation =
                OrganizationNameNormalizer
                        .normalizeForConfirmation(
                                entity.getName()
                        );

        String actualConfirmation =
                OrganizationNameNormalizer
                        .normalizeForConfirmation(
                                request.confirmationName()
                        );

        if (
                !expectedConfirmation.equals(
                        actualConfirmation
                )
        ) {
            throw new BadRequestException(
                    "Название организации для подтверждения "
                            + "не совпадает"
            );
        }

        return changeEnabled(
                entity,
                false,
                currentUser
        );
    }

    @Transactional
    public OrganizationResponse enable(
            UUID id,
            EnableOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        OrganizationEntity entity =
                findMutableOrganizationForUpdate(
                        id,
                        currentUser
                );

        requireExpectedVersion(
                entity,
                request.expectedVersion()
        );

        return changeEnabled(
                entity,
                true,
                currentUser
        );
    }

    private OrganizationResponse changeEnabled(
            OrganizationEntity entity,
            boolean newEnabled,
            SafeAiUserPrincipal currentUser
    ) {
        boolean oldEnabled =
                entity.isEnabled();

        if (oldEnabled == newEnabled) {
            return toResponse(entity);
        }

        long oldAuthVersion =
                entity.getAuthVersion();

        entity.setEnabled(newEnabled);

        if (!newEnabled) {
            entity.setAuthVersion(
                    Math.addExact(
                            oldAuthVersion,
                            1L
                    )
            );
        }

        OrganizationEntity saved =
                saveAndRefresh(entity);

        boolean sessionsRevoked = false;

        if (!newEnabled) {
            userSessionRevocationService
                    .revokeAllForOrganization(
                            saved.getId(),
                            RefreshTokenRevocationReason
                                    .ORGANIZATION_DISABLED
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
                currentUser,
                saved.getId(),
                AuditEventType
                        .ORGANIZATION_ENABLED_CHANGED,
                Map.of(
                        "organizationId",
                        saved.getId().toString(),
                        "organizationName",
                        saved.getName(),
                        "oldEnabled",
                        oldEnabled,
                        "newEnabled",
                        saved.isEnabled(),
                        "oldAuthVersion",
                        oldAuthVersion,
                        "newAuthVersion",
                        saved.getAuthVersion(),
                        "sessionsRevoked",
                        sessionsRevoked,
                        "requiresRelogin",
                        !newEnabled,
                        "version",
                        saved.getVersion()
                )
        );

        return toResponse(saved);
    }

    private OrganizationEntity
    findMutableOrganizationSnapshot(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        requireMutableOrganizationRequest(
                id,
                currentUser
        );

        return organizationRepository
                .findById(id)
                .orElseThrow(() ->
                        organizationNotFound(id)
                );
    }

    private OrganizationEntity
    findMutableOrganizationForUpdate(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        requireMutableOrganizationRequest(
                id,
                currentUser
        );

        return organizationRepository
                .findByIdForSecurityUpdate(id)
                .orElseThrow(() ->
                        organizationNotFound(id)
                );
    }

    private void requireMutableOrganizationRequest(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                id,
                "id не должен быть null"
        );

        requireSuperAdmin(currentUser);
        rejectPlatformOrganizationMutation(id);
    }

    private OrganizationEntity saveAndRefresh(
            OrganizationEntity organization
    ) {
        OrganizationEntity saved =
                organizationRepository
                        .saveAndFlush(
                                organization
                        );

        entityManager.refresh(saved);

        return saved;
    }

    private Pageable normalizePageable(
            Pageable pageable
    ) {
        if (
                pageable.getPageSize() < 1
                        || pageable.getPageSize()
                        > MAX_PAGE_SIZE
        ) {
            throw new BadRequestException(
                    "Размер страницы должен быть от 1 до "
                            + MAX_PAGE_SIZE
            );
        }

        validatePageableSort(pageable);

        Sort sort =
                pageable.getSort()
                        .isUnsorted()
                        ? Sort.by(
                        Sort.Order.desc(
                                "createdAt"
                        )
                )
                        : pageable.getSort();

        if (
                sort.stream()
                        .noneMatch(order ->
                                "id".equals(
                                        order.getProperty()
                                )
                        )
        ) {
            Sort.Direction tieBreakerDirection =
                    sort.stream()
                            .reduce(
                                    (
                                            first,
                                            second
                                    ) -> second
                            )
                            .map(
                                    Sort.Order::getDirection
                            )
                            .orElse(
                                    Sort.Direction.DESC
                            );

            sort = sort.and(
                    Sort.by(
                            new Sort.Order(
                                    tieBreakerDirection,
                                    "id"
                            )
                    )
            );
        }

        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                sort
        );
    }

    private void validatePageableSort(
            Pageable pageable
    ) {
        for (
                Sort.Order order
                : pageable.getSort()
        ) {
            if (
                    !ALLOWED_SORT_PROPERTIES
                            .contains(
                                    order.getProperty()
                            )
            ) {
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
        boolean protectedOrganization =
                isPlatformOrganization(
                        organization.getId()
                );

        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.isEnabled(),
                protectedOrganization
                        ? OrganizationType.PLATFORM
                        : OrganizationType.TENANT,
                protectedOrganization,
                organization.getVersion(),
                organization.getCreatedAt(),
                organization.getUpdatedAt()
        );
    }

    private OrganizationDirectoryResponse
    toDirectoryResponse(
            OrganizationEntity organization
    ) {
        boolean protectedOrganization =
                isPlatformOrganization(
                        organization.getId()
                );

        return new OrganizationDirectoryResponse(
                organization.getId(),
                organization.getName(),
                organization.isEnabled(),
                protectedOrganization
                        ? OrganizationType.PLATFORM
                        : OrganizationType.TENANT,
                protectedOrganization,
                organization.getVersion()
        );
    }

    private void requireExpectedVersion(
            OrganizationEntity organization,
            Long expectedVersion
    ) {
        if (
                expectedVersion == null
                        || expectedVersion < 0L
        ) {
            throw new BadRequestException(
                    "expectedVersion должен быть "
                            + "неотрицательным числом"
            );
        }

        if (
                organization.getVersion()
                        != expectedVersion
        ) {
            throw new OrganizationVersionConflictException(
                    organization.getId(),
                    expectedVersion,
                    organization.getVersion()
            );
        }
    }

    private boolean isPlatformOrganization(
            UUID organizationId
    ) {
        return platformProperties
                .organizationId()
                .equals(organizationId);
    }

    private UUID parseUuidOrNull(
            String value
    ) {
        if (
                value == null
                        || value.isBlank()
        ) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (
                IllegalArgumentException exception
        ) {
            return null;
        }
    }

    private boolean isSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return currentUser
                .getAuthorities()
                .stream()
                .map(
                        GrantedAuthority::getAuthority
                )
                .anyMatch(
                        "ROLE_SUPER_ADMIN"::equals
                );
    }

    private void requireSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (!isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Только SUPER_ADMIN может "
                            + "управлять организациями"
            );
        }
    }

    private void rejectPlatformOrganizationMutation(
            UUID organizationId
    ) {
        if (
                isPlatformOrganization(
                        organizationId
                )
        ) {
            throw new ForbiddenOperationException(
                    "Платформенную организацию нельзя "
                            + "изменять через обычный "
                            + "organization-management endpoint"
            );
        }
    }

    private boolean
    isOrganizationNameUniqueViolation(
            Throwable exception
    ) {
        return DatabaseConstraintClassifier
                .isUniqueViolation(
                        exception,
                        ORGANIZATION_NAME_UNIQUE_CONSTRAINTS
                                .toArray(
                                        String[]::new
                                )
                );
    }

    private ConflictException
    duplicateOrganizationName(
            String name
    ) {
        return new ConflictException(
                "Организация с таким названием "
                        + "уже существует: "
                        + name
        );
    }

    private ResourceNotFoundException
    organizationNotFound(
            UUID id
    ) {
        return new ResourceNotFoundException(
                "Организация не найдена: "
                        + id
        );
    }
}
