package ru.safeai.gateway.organization.service;

import jakarta.persistence.EntityManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.identity.session.SessionRevocationPort;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.DisableOrganizationRequest;
import ru.safeai.gateway.organization.dto.EnableOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationDirectoryResponse;
import ru.safeai.gateway.organization.dto.OrganizationDisableImpactResponse;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
import ru.safeai.gateway.organization.repository.OrganizationImpactQueryRepository;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.util.List;
import java.util.UUID;

@Service
@EnableConfigurationProperties(PlatformProperties.class)
public class OrganizationService {

    private final OrganizationCommandOperations commands;
    private final OrganizationQueryOperations queries;

    /** Keep the original dependency-facing constructor and wiring contract. */
    public OrganizationService(
            OrganizationRepository organizationRepository,
            OrganizationImpactQueryRepository impactQueryRepository,
            AuditEventService auditEventService,
            ApplicationEventPublisher eventPublisher,
            PlatformProperties platformProperties,
            SessionRevocationPort userSessionRevocationService,
            EntityManager entityManager
    ) {
        OrganizationAccessPolicy accessPolicy =
                new OrganizationAccessPolicy(platformProperties);
        OrganizationResponseMapper responseMapper =
                new OrganizationResponseMapper(accessPolicy);
        this.commands = new OrganizationCommandOperations(
                organizationRepository,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                entityManager,
                accessPolicy,
                responseMapper
        );
        this.queries = new OrganizationQueryOperations(
                organizationRepository,
                impactQueryRepository,
                accessPolicy,
                responseMapper
        );
    }

    @Transactional
    public OrganizationResponse create(
            CreateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        return commands.create(request, currentUser);
    }

    @Transactional(readOnly = true)
    public Page<OrganizationResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        return queries.findAll(currentUser, pageable);
    }

    @Transactional(readOnly = true)
    public List<OrganizationDirectoryResponse> findDirectory(
            String query,
            int limit,
            SafeAiUserPrincipal currentUser
    ) {
        return queries.findDirectory(query, limit, currentUser);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findCurrentOrganization(
            SafeAiUserPrincipal currentUser
    ) {
        return queries.findCurrentOrganization(currentUser);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse findById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        return queries.findById(id, currentUser);
    }

    // One consistent PostgreSQL snapshot for entity version and all counters.
    // This DOES NOT guarantee that state stays unchanged until a later disable.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrganizationDisableImpactResponse getDisableImpact(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        return queries.getDisableImpact(id, currentUser);
    }

    @Transactional
    public OrganizationResponse updateName(
            UUID id,
            UpdateOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        return commands.updateName(id, request, currentUser);
    }

    @Transactional
    public OrganizationResponse disable(
            UUID id,
            DisableOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        return commands.disable(id, request, currentUser);
    }

    @Transactional
    public OrganizationResponse enable(
            UUID id,
            EnableOrganizationRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        return commands.enable(id, request, currentUser);
    }
}
