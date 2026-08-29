package ru.safeai.gateway.user.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.safeai.gateway.common.pagination.StablePageableNormalizer;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.user.dto.UserDetailsResponse;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.dto.UserStatisticsResponse;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class UserQueryOperations {

    private static final int MAX_PAGE_SIZE = 100;

    private static final Sort DEFAULT_PAGE_SORT =
            Sort.by(Sort.Order.desc("createdAt"));

    private static final Set<String> ALLOWED_SORT_PROPERTIES =
            Set.of(
                    "createdAt",
                    "email",
                    "fullName",
                    "enabled",
                    "lastLoginAt"
            );

    private final UserRepository userRepository;
    private final UserAccessPolicy accessPolicy;
    private final UserEntityAccess entityAccess;
    private final UserResponseMapper responseMapper;

    UserQueryOperations(
            UserRepository userRepository,
            UserAccessPolicy accessPolicy,
            UserEntityAccess entityAccess,
            UserResponseMapper responseMapper
    ) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.entityAccess = entityAccess;
        this.responseMapper = responseMapper;
    }

    Page<UserResponse> findAll(
            SafeAiUserPrincipal currentUser,
            String role,
            Pageable pageable
    ) {
        accessPolicy.requireUserManager(currentUser);
        Objects.requireNonNull(
                pageable,
                "pageable не должен быть null"
        );

        Pageable stablePageable = normalizePageable(pageable);
        String normalizedRole = accessPolicy.normalizeListRole(role);

        Page<UUID> idPage;
        if (accessPolicy.isSuperAdmin(currentUser)) {
            idPage = normalizedRole == null
                    ? userRepository.findAllIds(stablePageable)
                    : userRepository.findAllIdsByRole(
                            normalizedRole,
                            stablePageable
                    );
        } else {
            UUID organizationId = currentUser.getOrganizationId();
            idPage = normalizedRole == null
                    ? userRepository.findAllIdsByOrganizationId(
                            organizationId,
                            stablePageable
                    )
                    : userRepository.findAllIdsByOrganizationIdAndRole(
                            organizationId,
                            normalizedRole,
                            stablePageable
                    );
        }

        if (idPage.isEmpty()) {
            return new PageImpl<>(
                    List.of(),
                    stablePageable,
                    idPage.getTotalElements()
            );
        }

        List<UserEntity> users =
                userRepository.findAllByIdsWithRolesAndOrganization(
                        idPage.getContent()
                );

        Map<UUID, UserEntity> usersById = new HashMap<>();
        for (UserEntity user : users) {
            usersById.put(user.getId(), user);
        }

        List<UserResponse> content =
                new ArrayList<>(idPage.getNumberOfElements());

        for (UUID userId : idPage.getContent()) {
            UserEntity user = usersById.get(userId);
            if (user == null) {
                throw new IllegalStateException(
                        "Не удалось загрузить пользователя "
                                + "из page snapshot: " + userId
                );
            }
            content.add(responseMapper.toResponse(user));
        }

        return new PageImpl<>(
                content,
                stablePageable,
                idPage.getTotalElements()
        );
    }

    UserDetailsResponse findDetailsById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        accessPolicy.requireUserManager(currentUser);
        return responseMapper.toDetailsResponse(
                entityAccess.findVisible(id, currentUser)
        );
    }

    UserStatisticsResponse statistics(
            SafeAiUserPrincipal currentUser
    ) {
        accessPolicy.requireUserManager(currentUser);

        if (accessPolicy.isSuperAdmin(currentUser)) {
            return new UserStatisticsResponse(
                    userRepository.count(),
                    userRepository.countByRole(
                            SystemRole.ADMIN.roleName()
                    ),
                    userRepository.countByRole(
                            SystemRole.USER.roleName()
                    ),
                    userRepository.countByEnabled(true),
                    userRepository.countByEnabled(false)
            );
        }

        UUID organizationId = currentUser.getOrganizationId();

        return new UserStatisticsResponse(
                userRepository.countByOrganization_Id(organizationId),
                userRepository.countByOrganizationIdAndRole(
                        organizationId,
                        SystemRole.ADMIN.roleName()
                ),
                userRepository.countByOrganizationIdAndRole(
                        organizationId,
                        SystemRole.USER.roleName()
                ),
                userRepository.countByOrganization_IdAndEnabled(
                        organizationId,
                        true
                ),
                userRepository.countByOrganization_IdAndEnabled(
                        organizationId,
                        false
                )
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
                        .DESCENDING
        );
    }
}
