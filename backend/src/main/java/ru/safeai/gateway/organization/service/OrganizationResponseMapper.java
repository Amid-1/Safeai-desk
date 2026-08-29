package ru.safeai.gateway.organization.service;

import ru.safeai.gateway.organization.dto.OrganizationDirectoryResponse;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.OrganizationType;
import ru.safeai.gateway.organization.entity.OrganizationEntity;

final class OrganizationResponseMapper {

    private final OrganizationAccessPolicy accessPolicy;

    OrganizationResponseMapper(
            OrganizationAccessPolicy accessPolicy
    ) {
        this.accessPolicy = accessPolicy;
    }

    OrganizationResponse toResponse(
            OrganizationEntity organization
    ) {
        boolean protectedOrganization =
                accessPolicy.isPlatformOrganization(
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

    OrganizationDirectoryResponse toDirectoryResponse(
            OrganizationEntity organization
    ) {
        boolean protectedOrganization =
                accessPolicy.isPlatformOrganization(
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
}
