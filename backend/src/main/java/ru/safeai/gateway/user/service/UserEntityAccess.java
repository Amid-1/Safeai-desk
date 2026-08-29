package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.Hibernate;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class UserEntityAccess {

    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final UserAccessPolicy accessPolicy;

    UserEntityAccess(
            UserRepository userRepository,
            EntityManager entityManager,
            UserAccessPolicy accessPolicy
    ) {
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.accessPolicy = accessPolicy;
    }

    UserEntity findVisible(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(id, "id не должен быть null");
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (accessPolicy.isSuperAdmin(currentUser)) {
            return userRepository
                    .findByIdWithRolesAndOrganization(id)
                    .orElseThrow(() -> userNotFound(id));
        }

        return userRepository
                .findByIdAndOrganizationId(
                        id,
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() -> userNotFound(id));
    }

    /**
     * Lock order is intentionally preserved:
     * organization PESSIMISTIC_WRITE -> user PESSIMISTIC_WRITE.
     */
    UserEntity findForSecurityMutation(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        UserEntity visibleSnapshot = findVisible(id, currentUser);
        OrganizationEntity visibleOrganization =
                visibleSnapshot.getOrganization();
        UUID organizationId = visibleOrganization.getId();

        detachVisibleSecuritySnapshot(
                visibleSnapshot,
                visibleOrganization
        );

        lockOrganization(organizationId);

        UserEntity locked = userRepository
                .findByIdForSecurityUpdate(id)
                .orElseThrow(() -> userNotFound(id));

        UUID lockedOrganizationId =
                locked.getOrganization().getId();

        Hibernate.initialize(locked.getRoles());

        if (!organizationId.equals(lockedOrganizationId)) {
            throw new IllegalStateException(
                    "Организация пользователя изменилась "
                            + "во время блокировки: " + id
            );
        }

        if (!accessPolicy.isSuperAdmin(currentUser)
                && !currentUser.getOrganizationId()
                .equals(lockedOrganizationId)) {
            throw userNotFound(id);
        }

        return locked;
    }

    OrganizationEntity lockOrganization(
            UUID organizationId
    ) {
        OrganizationEntity organization = entityManager.find(
                OrganizationEntity.class,
                organizationId,
                LockModeType.PESSIMISTIC_WRITE
        );

        if (organization == null) {
            throw new ResourceNotFoundException(
                    "Организация не найдена: " + organizationId
            );
        }

        return organization;
    }

    void assertNotLastEnabledAdmin(
            UserEntity user
    ) {
        List<UserEntity> enabledAdmins =
                userRepository.findEnabledAdminsForUpdate(
                        user.getOrganization().getId()
                );

        if (enabledAdmins.size() <= 1) {
            throw new ForbiddenOperationException(
                    "Нельзя изменить или удалить "
                            + "последнего активного администратора "
                            + "организации"
            );
        }
    }

    ResourceNotFoundException userNotFound(
            UUID id
    ) {
        return new ResourceNotFoundException(
                "Пользователь не найден: " + id
        );
    }

    private void detachVisibleSecuritySnapshot(
            UserEntity visibleSnapshot,
            OrganizationEntity visibleOrganization
    ) {
        if (entityManager.contains(visibleSnapshot)) {
            entityManager.detach(visibleSnapshot);
        }

        if (entityManager.contains(visibleOrganization)) {
            entityManager.detach(visibleOrganization);
        }
    }
}
