package ru.safeai.gateway.user.service;

import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.dto.UserDetailsResponse;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.mapper.UserRoleMapper;

final class UserResponseMapper {

    UserResponse toResponse(
            UserEntity entity
    ) {
        return new UserResponse(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getEmail(),
                entity.getFullName(),
                entity.isEnabled(),
                UserRoleMapper.toRoleNames(entity),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastLoginAt()
        );
    }

    UserDetailsResponse toDetailsResponse(
            UserEntity entity
    ) {
        OrganizationEntity organization = entity.getOrganization();

        return new UserDetailsResponse(
                entity.getId(),
                organization.getId(),
                organization.getName(),
                entity.getEmail(),
                entity.getFullName(),
                entity.isEnabled(),
                UserRoleMapper.toRoleNames(entity),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastLoginAt()
        );
    }
}
