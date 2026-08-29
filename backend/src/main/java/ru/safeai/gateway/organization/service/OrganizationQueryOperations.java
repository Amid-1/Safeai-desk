package ru.safeai.gateway.organization.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.pagination.StablePageableNormalizer;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.OrganizationDirectoryResponse;
import ru.safeai.gateway.organization.dto.OrganizationDisableImpactResponse;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationImpactQueryRepository;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class OrganizationQueryOperations {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_DIRECTORY_LIMIT = 50;

    private static final Sort DEFAULT_PAGE_SORT =
            Sort.by(Sort.Order.desc("createdAt"));

    private static final Set<String> ALLOWED_SORT_PROPERTIES =
            Set.of(
                    "createdAt",
                    "name",
                    "enabled",
                    "updatedAt"
            );

    private final OrganizationRepository organizationRepository;
    private final OrganizationImpactQueryRepository impactQueryRepository;
    private final OrganizationAccessPolicy accessPolicy;
    private final OrganizationResponseMapper responseMapper;

    OrganizationQueryOperations(
            OrganizationRepository organizationRepository,
            OrganizationImpactQueryRepository impactQueryRepository,
            OrganizationAccessPolicy accessPolicy,
            OrganizationResponseMapper responseMapper
    ) {
        this.organizationRepository = organizationRepository;
        this.impactQueryRepository = impactQueryRepository;
        this.accessPolicy = accessPolicy;
        this.responseMapper = responseMapper;
    }

    Page<OrganizationResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        accessPolicy.requireOrganizationReader(currentUser);
        Objects.requireNonNull(
                pageable,
                "pageable не должен быть null"
        );

        Pageable stablePageable = normalizePageable(pageable);

        if (accessPolicy.isSuperAdmin(currentUser)) {
            return organizationRepository
                    .findAllStable(stablePageable)
                    .map(responseMapper::toResponse);
        }

        if (stablePageable.getOffset() > 0L) {
            return new PageImpl<>(
                    List.of(),
                    stablePageable,
                    1L
            );
        }

        UUID organizationId = currentUser.getOrganizationId();

        OrganizationEntity organization =
                organizationRepository
                        .findById(organizationId)
                        .orElseThrow(() ->
                                accessPolicy.organizationNotFound(
                                        organizationId
                                )
                        );

        return new PageImpl<>(
                List.of(responseMapper.toResponse(organization)),
                stablePageable,
                1L
        );
    }

    List<OrganizationDirectoryResponse> findDirectory(
            String query,
            int limit,
            SafeAiUserPrincipal currentUser
    ) {
        accessPolicy.requireSuperAdmin(currentUser);

        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim();

        if (normalizedQuery.length() > 255) {
            throw new BadRequestException(
                    "Поисковый запрос не должен превышать 255 символов"
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

        UUID exactId = parseUuidOrNull(normalizedQuery);

        if (exactId != null) {
            return organizationRepository
                    .findById(exactId)
                    .map(responseMapper::toDirectoryResponse)
                    .map(List::of)
                    .orElseGet(List::of);
        }

        Page<OrganizationEntity> page =
                normalizedQuery.isEmpty()
                        ? organizationRepository.findAll(pageable)
                        : organizationRepository.searchDirectoryByName(
                                escapeDirectoryLikeLiteral(normalizedQuery),
                                pageable
                        );

        return page.getContent()
                .stream()
                .map(responseMapper::toDirectoryResponse)
                .toList();
    }

    OrganizationResponse findCurrentOrganization(
            SafeAiUserPrincipal currentUser
    ) {
        accessPolicy.requireOrganizationReader(currentUser);

        UUID organizationId = currentUser.getOrganizationId();

        return organizationRepository
                .findById(organizationId)
                .map(responseMapper::toResponse)
                .orElseThrow(() ->
                        accessPolicy.organizationNotFound(
                                organizationId
                        )
                );
    }

    OrganizationResponse findById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(id, "id не должен быть null");
        accessPolicy.requireOrganizationReader(currentUser);

        if (!accessPolicy.isSuperAdmin(currentUser)
                && !currentUser.getOrganizationId().equals(id)) {
            /*
             * 404 вместо 403 не раскрывает существование
             * чужой tenant organization.
             */
            throw accessPolicy.organizationNotFound(id);
        }

        return organizationRepository
                .findById(id)
                .map(responseMapper::toResponse)
                .orElseThrow(() ->
                        accessPolicy.organizationNotFound(id)
                );
    }

    OrganizationDisableImpactResponse getDisableImpact(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        accessPolicy.requireMutableOrganizationRequest(
                id,
                currentUser
        );

        OrganizationEntity entity =
                organizationRepository
                        .findById(id)
                        .orElseThrow(() ->
                                accessPolicy.organizationNotFound(id)
                        );

        OrganizationImpactQueryRepository.ImpactSnapshot impact =
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

    private static Pageable normalizePageable(
            Pageable pageable
    ) {
        return StablePageableNormalizer.normalize(
                pageable,
                MAX_PAGE_SIZE,
                ALLOWED_SORT_PROPERTIES,
                DEFAULT_PAGE_SORT,
                "id",
                StablePageableNormalizer
                        .TieBreakerDirectionPolicy
                        .FOLLOW_LAST_SORT_DIRECTION
        );
    }

    private static String escapeDirectoryLikeLiteral(
            String value
    ) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private static UUID parseUuidOrNull(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
