package ru.safeai.gateway.auth.integration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.Hibernate;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Test-only transactional fixture for refresh/login concurrency tests.
 *
 * <p>This class deliberately lives under {@code src/test}. It models only
 * the persistence-critical part of a user security mutation so
 * {@link AuthPostgresConcurrencyIT} can race it against login/refresh.</p>
 *
 * <p>Production authorization, optimistic-lock checks, audit and API
 * behavior remain owned exclusively by {@code UserService}.</p>
 */
class UserSecurityMutationTestService {

    private final EntityManager entityManager;

    private final UserRepository userRepository;

    private final RoleRepository roleRepository;

    private final UserSessionRevocationService
            userSessionRevocationService;

    private final Clock clock;

    UserSecurityMutationTestService(
            EntityManager entityManager,
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserSessionRevocationService
                    userSessionRevocationService,
            Clock clock
    ) {
        this.entityManager =
                Objects.requireNonNull(
                        entityManager,
                        "entityManager не должен быть null"
                );

        this.userRepository =
                Objects.requireNonNull(
                        userRepository,
                        "userRepository не должен быть null"
                );

        this.roleRepository =
                Objects.requireNonNull(
                        roleRepository,
                        "roleRepository не должен быть null"
                );

        this.userSessionRevocationService =
                Objects.requireNonNull(
                        userSessionRevocationService,
                        "userSessionRevocationService не должен быть null"
                );

        this.clock =
                Objects.requireNonNull(
                        clock,
                        "clock не должен быть null"
                );
    }

    @Transactional
    public long resetPassword(
            UUID userId,
            String encodedPassword
    ) {
        if (encodedPassword == null
                || encodedPassword.isBlank()) {

            throw new IllegalArgumentException(
                    "encodedPassword не должен быть пустым"
            );
        }

        UserEntity user =
                lockUserInProductionOrder(
                        userId
                );

        user.setPasswordHash(
                encodedPassword
        );

        long tokenVersion =
                incrementTokenVersion(
                        user
                );

        userSessionRevocationService
                .revokeAllForUser(
                        userId,
                        RefreshTokenRevocationReason
                                .PASSWORD_RESET
                );

        return tokenVersion;
    }

    @Transactional
    public long replaceRole(
            UUID userId,
            String roleName
    ) {
        Objects.requireNonNull(
                roleName,
                "roleName не должен быть null"
        );

        SystemRole systemRole;

        try {
            systemRole =
                    SystemRole.parse(
                            roleName
                    );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Неизвестная системная роль: "
                            + roleName,
                    exception
            );
        }

        RoleEntity role =
                roleRepository
                        .findByName(
                                systemRole.roleName()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Роль не найдена: "
                                                + systemRole
                                                .roleName()
                                )
                        );

        UserEntity user =
                lockUserInProductionOrder(
                        userId
                );

        Hibernate.initialize(
                user.getRoles()
        );

        user.getRoles()
                .clear();

        user.getRoles()
                .add(
                        role
                );

        long tokenVersion =
                incrementTokenVersion(
                        user
                );

        userSessionRevocationService
                .revokeAllForUser(
                        userId,
                        RefreshTokenRevocationReason
                                .ROLE_CHANGED
                );

        return tokenVersion;
    }

    @Transactional
    public long disableUser(
            UUID userId
    ) {
        UserEntity user =
                lockUserInProductionOrder(
                        userId
                );

        user.setEnabled(
                false
        );

        user.setDisabledAt(
                clock.instant()
        );

        long tokenVersion =
                incrementTokenVersion(
                        user
                );

        userSessionRevocationService
                .revokeAllForUser(
                        userId,
                        RefreshTokenRevocationReason
                                .USER_DISABLED
                );

        return tokenVersion;
    }

    /**
     * Mirrors the production lock order used by UserService:
     *
     * <pre>
     * organization PESSIMISTIC_WRITE
     *     ->
     * user PESSIMISTIC_WRITE
     * </pre>
     */
    private UserEntity lockUserInProductionOrder(
            UUID userId
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        UserEntity visibleSnapshot =
                userRepository
                        .findByIdWithOrganization(
                                userId
                        )
                        .orElseThrow(() ->
                                userNotFound(
                                        userId
                                )
                        );

        OrganizationEntity visibleOrganization =
                visibleSnapshot
                        .getOrganization();

        UUID organizationId =
                visibleOrganization
                        .getId();

        if (entityManager.contains(
                visibleSnapshot
        )) {
            entityManager.detach(
                    visibleSnapshot
            );
        }

        if (entityManager.contains(
                visibleOrganization
        )) {
            entityManager.detach(
                    visibleOrganization
            );
        }

        OrganizationEntity lockedOrganization =
                entityManager.find(
                        OrganizationEntity.class,
                        organizationId,
                        LockModeType.PESSIMISTIC_WRITE
                );

        if (lockedOrganization == null) {
            throw new ResourceNotFoundException(
                    "Организация не найдена: "
                            + organizationId
            );
        }

        UserEntity lockedUser =
                userRepository
                        .findByIdForSecurityUpdate(
                                userId
                        )
                        .orElseThrow(() ->
                                userNotFound(
                                        userId
                                )
                        );

        if (!organizationId.equals(
                lockedUser
                        .getOrganization()
                        .getId()
        )) {
            throw new IllegalStateException(
                    "Организация пользователя изменилась "
                            + "во время блокировки: "
                            + userId
            );
        }

        return lockedUser;
    }

    private long incrementTokenVersion(
            UserEntity user
    ) {
        long nextVersion =
                Math.addExact(
                        user.getTokenVersion(),
                        1L
                );

        user.setTokenVersion(
                nextVersion
        );

        return nextVersion;
    }

    private ResourceNotFoundException userNotFound(
            UUID userId
    ) {
        return new ResourceNotFoundException(
                "Пользователь не найден: "
                        + userId
        );
    }
}