package ru.safeai.gateway.organization.service;

import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.OrganizationVersionConflictException;
import ru.safeai.gateway.common.persistence.DatabaseConstraintClassifier;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.DisableOrganizationRequest;
import ru.safeai.gateway.organization.dto.EnableOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class OrganizationCommandOperations {

    private static final Set<String> ORGANIZATION_NAME_UNIQUE_CONSTRAINTS =
            Set.of(
                    "ux_organizations_name_normalized",
                    "ux_organizations_normalized_name",
                    "uq_organizations_name_normalized",
                    "uq_organizations_normalized_name"
            );

    private final OrganizationRepository organizationRepository;
    private final AuditEventService auditEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserSessionRevocationService userSessionRevocationService;
    private final EntityManager entityManager;
    private final OrganizationAccessPolicy accessPolicy;
    private final OrganizationResponseMapper responseMapper;

    OrganizationCommandOperations(
            OrganizationRepository organizationRepository,
            AuditEventService auditEventService,
            ApplicationEventPublisher eventPublisher,
            UserSessionRevocationService userSessionRevocationService,
            EntityManager entityManager,
            OrganizationAccessPolicy accessPolicy,
            OrganizationResponseMapper responseMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.auditEventService = auditEventService;
        this.eventPublisher = eventPublisher;
        this.userSessionRevocationService = userSessionRevocationService;
        this.entityManager = entityManager;
        this.accessPolicy = accessPolicy;
        this.responseMapper = responseMapper;
    }

    OrganizationResponse create(
            CreateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );
        accessPolicy.requireSuperAdmin(currentUser);

        String canonicalName =
                OrganizationNameNormalizer.canonicalize(
                        request.name()
                );

        /*
         * PostgreSQL canonicalization + UNIQUE indexes are the correctness
         * boundary. There is intentionally no Java uniqueness preflight.
         */
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

        return responseMapper.toResponse(saved);
    }

    OrganizationResponse updateName(
            UUID id,
            UpdateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        OrganizationEntity entity =
                findMutableOrganizationForUpdate(id, currentUser);

        requireExpectedVersion(
                entity,
                request.expectedVersion()
        );

        String canonicalName =
                OrganizationNameNormalizer.canonicalize(
                        request.name()
                );

        if (entity.getName().equals(canonicalName)) {
            return responseMapper.toResponse(entity);
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

        return responseMapper.toResponse(saved);
    }

    OrganizationResponse disable(
            UUID id,
            DisableOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        OrganizationEntity entity =
                findMutableOrganizationForUpdate(id, currentUser);

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

        if (!expectedConfirmation.equals(actualConfirmation)) {
            throw new BadRequestException(
                    "Название организации для подтверждения не совпадает"
            );
        }

        return changeEnabled(
                entity,
                false,
                currentUser
        );
    }

    OrganizationResponse enable(
            UUID id,
            EnableOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        OrganizationEntity entity =
                findMutableOrganizationForUpdate(id, currentUser);

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
        boolean oldEnabled = entity.isEnabled();

        if (oldEnabled == newEnabled) {
            return responseMapper.toResponse(entity);
        }

        long oldAuthVersion = entity.getAuthVersion();

        entity.setEnabled(newEnabled);

        if (!newEnabled) {
            entity.setAuthVersion(
                    Math.addExact(
                            oldAuthVersion,
                            1L
                    )
            );
        }

        OrganizationEntity saved = saveAndRefresh(entity);
        boolean sessionsRevoked = false;

        /*
         * Disable fences NEW authenticated work after commit through
         * enabled/authVersion. Already-started ChatTurn processing is not
         * cancelled here because that needs its own fencing protocol.
         */
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
                AuditEventType.ORGANIZATION_ENABLED_CHANGED,
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

        return responseMapper.toResponse(saved);
    }

    private OrganizationEntity findMutableOrganizationForUpdate(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        accessPolicy.requireMutableOrganizationRequest(
                id,
                currentUser
        );

        return organizationRepository
                .findByIdForSecurityUpdate(id)
                .orElseThrow(() ->
                        accessPolicy.organizationNotFound(id)
                );
    }

    private OrganizationEntity saveAndRefresh(
            OrganizationEntity organization
    ) {
        OrganizationEntity saved =
                organizationRepository
                        .saveAndFlush(organization);

        entityManager.refresh(saved);
        return saved;
    }

    private static void requireExpectedVersion(
            OrganizationEntity organization,
            Long expectedVersion
    ) {
        if (expectedVersion == null || expectedVersion < 0L) {
            throw new BadRequestException(
                    "expectedVersion должен быть неотрицательным числом"
            );
        }

        if (organization.getVersion() != expectedVersion) {
            throw new OrganizationVersionConflictException(
                    organization.getId(),
                    expectedVersion,
                    organization.getVersion()
            );
        }
    }

    private static boolean isOrganizationNameUniqueViolation(
            Throwable exception
    ) {
        return DatabaseConstraintClassifier.isUniqueViolation(
                exception,
                ORGANIZATION_NAME_UNIQUE_CONSTRAINTS
                        .toArray(String[]::new)
        );
    }

    private static ConflictException duplicateOrganizationName(
            String name
    ) {
        return new ConflictException(
                "Организация с таким названием уже существует: " + name
        );
    }
}
