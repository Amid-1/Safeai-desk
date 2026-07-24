package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.PermanentDeleteUserRequest;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.dto.UserDetailsResponse;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.dto.UserStatisticsResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.event.UserSecurityStateChangedEvent;
import ru.safeai.gateway.user.mapper.UserRoleMapper;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private static final Set<String> SYSTEM_ROLES =
            Set.of(
                    ROLE_USER,
                    ROLE_ADMIN,
                    ROLE_SUPER_ADMIN
            );

    private static final Set<String>
            SUPER_ADMIN_ASSIGNABLE_ROLES =
            Set.of(
                    ROLE_USER,
                    ROLE_ADMIN
            );

    private static final Set<String>
            ADMIN_ASSIGNABLE_ROLES =
            Set.of(ROLE_USER);

    private static final Set<String> LIST_FILTER_ROLES =
            Set.of(
                    ROLE_USER,
                    ROLE_ADMIN
            );

    private static final Set<String>
            ALLOWED_SORT_PROPERTIES =
            Set.of(
                    "createdAt",
                    "email",
                    "fullName",
                    "enabled",
                    "lastLoginAt"
            );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventService auditEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserSessionRevocationService
            userSessionRevocationService;
    private final PlatformProperties platformProperties;

    @Transactional
    public UserResponse create(
            CreateUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        String email = normalizeEmail(request.email());

        UUID targetOrganizationId =
                resolveTargetOrganizationId(
                        request.organizationId(),
                        currentUser
                );

        rejectPlatformOrganizationCreation(
                targetOrganizationId
        );

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException(
                    "Пользователь с таким email уже существует: "
                            + email
            );
        }

        OrganizationEntity organization =
                organizationRepository
                        .findById(targetOrganizationId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Организация не найдена: "
                                                + targetOrganizationId
                                )
                        );

        if (!organization.isEnabled()) {
            throw new ForbiddenOperationException(
                    "Нельзя создать пользователя "
                            + "в отключенной организации"
            );
        }

        Set<String> requestedRoles =
                request.roles() == null
                        || request.roles().isEmpty()
                        ? Set.of(ROLE_USER)
                        : request.roles();

        Set<RoleEntity> roles =
                resolveUserManagementRoles(
                        requestedRoles,
                        currentUser
                );

        UserEntity entity = new UserEntity();
        entity.setOrganization(organization);
        entity.setEmail(email);
        entity.setPasswordHash(
                passwordEncoder.encode(request.password())
        );
        entity.setFullName(
                normalizeFullName(request.fullName())
        );
        entity.setEnabled(true);
        entity.setRoles(new HashSet<>(roles));

        UserEntity saved;

        try {
            saved = userRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    "Пользователь с таким email уже существует: "
                            + email
            );
        }

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_CREATED,
                Map.of(
                        "targetUserId",
                        saved.getId().toString(),
                        "targetUserEmail",
                        saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization()
                                .getId()
                                .toString(),
                        "roles",
                        UserRoleMapper.toRoleNames(saved)
                )
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> findAll(
            SafeAiUserPrincipal currentUser,
            String role,
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

        validatePageableSort(pageable);

        String normalizedRole =
                normalizeListRole(role);

        Page<UUID> idPage;

        if (isSuperAdmin(currentUser)) {
            idPage = normalizedRole == null
                    ? userRepository.findAllIds(pageable)
                    : userRepository.findAllIdsByRole(
                    normalizedRole,
                    pageable
            );
        } else {
            idPage = normalizedRole == null
                    ? userRepository
                    .findAllIdsByOrganizationId(
                            currentUser.getOrganizationId(),
                            pageable
                    )
                    : userRepository
                    .findAllIdsByOrganizationIdAndRole(
                            currentUser.getOrganizationId(),
                            normalizedRole,
                            pageable
                    );
        }

        if (idPage.isEmpty()) {
            return new PageImpl<>(
                    List.of(),
                    pageable,
                    idPage.getTotalElements()
            );
        }

        List<UserEntity> users =
                userRepository
                        .findAllByIdsWithRolesAndOrganization(
                                idPage.getContent()
                        );

        Map<UUID, UserEntity> usersById =
                new HashMap<>();

        for (UserEntity user : users) {
            usersById.put(
                    user.getId(),
                    user
            );
        }

        List<UserResponse> content =
                new ArrayList<>(idPage.getSize());

        for (UUID userId : idPage.getContent()) {
            UserEntity user = usersById.get(userId);

            if (user != null) {
                content.add(toResponse(user));
            }
        }

        return new PageImpl<>(
                content,
                pageable,
                idPage.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public UserDetailsResponse findDetailsById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        return toDetailsResponse(
                findUserVisibleForCurrentUser(
                        id,
                        currentUser
                )
        );
    }

    @Transactional(readOnly = true)
    public UserStatisticsResponse statistics(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (isSuperAdmin(currentUser)) {
            return new UserStatisticsResponse(
                    userRepository.count(),
                    userRepository.countByRole(ROLE_ADMIN),
                    userRepository.countByRole(ROLE_USER),
                    userRepository.countByEnabled(true),
                    userRepository.countByEnabled(false)
            );
        }

        UUID organizationId =
                currentUser.getOrganizationId();

        return new UserStatisticsResponse(
                userRepository
                        .countByOrganization_Id(
                                organizationId
                        ),
                userRepository
                        .countByOrganizationIdAndRole(
                                organizationId,
                                ROLE_ADMIN
                        ),
                userRepository
                        .countByOrganizationIdAndRole(
                                organizationId,
                                ROLE_USER
                        ),
                userRepository
                        .countByOrganization_IdAndEnabled(
                                organizationId,
                                true
                        ),
                userRepository
                        .countByOrganization_IdAndEnabled(
                                organizationId,
                                false
                        )
        );
    }

    @Transactional
    public UserResponse updateUser(
            UUID id,
            UpdateUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        UserEntity user =
                findUserVisibleForCurrentUser(
                        id,
                        currentUser
                );

        rejectSuperAdminMutation(user);

        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException(
                    "Нельзя редактировать самого себя "
                            + "через user-management"
            );
        }

        rejectAdminManagingAdmin(
                user,
                currentUser
        );

        String normalizedEmail =
                normalizeEmail(request.email());

        String normalizedFullName =
                normalizeFullName(request.fullName());

        boolean emailChanged =
                !user.getEmail().equals(normalizedEmail);

        boolean fullNameChanged =
                !Objects.equals(
                        user.getFullName(),
                        normalizedFullName
                );

        if (!emailChanged && !fullNameChanged) {
            return toResponse(user);
        }

        if (emailChanged
                && userRepository
                .existsByEmailIgnoreCase(
                        normalizedEmail
                )) {
            throw new ConflictException(
                    "Пользователь с таким email уже существует: "
                            + normalizedEmail
            );
        }

        String oldEmail = user.getEmail();
        String oldFullName = user.getFullName();

        user.setEmail(normalizedEmail);
        user.setFullName(normalizedFullName);

        if (emailChanged) {
            user.setTokenVersion(
                    Math.addExact(
                            user.getTokenVersion(),
                            1L
                    )
            );
        }

        UserEntity saved;

        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    "Пользователь с такими данными "
                            + "уже существует"
            );
        }

        if (emailChanged) {
            userSessionRevocationService
                    .revokeAllForUser(
                            saved.getId(),
                            RefreshTokenRevocationReason
                                    .EMAIL_CHANGED
                    );

            publishSecurityStateChanged(
                    saved.getId()
            );
        }

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_UPDATED,
                Map.of(
                        "targetUserId",
                        saved.getId().toString(),
                        "oldEmail",
                        oldEmail,
                        "newEmail",
                        saved.getEmail(),
                        "oldFullName",
                        oldFullName == null
                                ? ""
                                : oldFullName,
                        "newFullName",
                        saved.getFullName() == null
                                ? ""
                                : saved.getFullName(),
                        "emailChanged",
                        emailChanged,
                        "fullNameChanged",
                        fullNameChanged,
                        "sessionsRevoked",
                        emailChanged
                )
        );

        return toResponse(saved);
    }

    @Transactional
    public UserResponse updateEnabled(
            UUID id,
            UpdateUserEnabledRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        UserEntity user =
                findUserVisibleForCurrentUser(
                        id,
                        currentUser
                );

        rejectSuperAdminMutation(user);

        boolean oldEnabled = user.isEnabled();
        boolean newEnabled =
                Boolean.TRUE.equals(request.enabled());

        if (user.getId().equals(currentUser.getId())
                && !newEnabled) {
            throw new ForbiddenOperationException(
                    "Нельзя отключить самого себя"
            );
        }

        rejectAdminManagingAdmin(
                user,
                currentUser
        );

        if (!newEnabled && isEnabledAdmin(user)) {
            List<UserEntity> enabledAdmins =
                    userRepository
                            .findEnabledAdminsForUpdate(
                                    user.getOrganization()
                                            .getId()
                            );

            if (enabledAdmins.size() <= 1) {
                throw new ForbiddenOperationException(
                        "Нельзя отключить последнего активного "
                                + "администратора организации"
                );
            }
        }

        boolean changed =
                oldEnabled != newEnabled;

        if (changed) {
            user.setEnabled(newEnabled);
            user.setTokenVersion(
                    Math.addExact(
                            user.getTokenVersion(),
                            1L
                    )
            );
        }

        UserEntity saved =
                userRepository.save(user);

        if (changed) {
            if (!newEnabled) {
                userSessionRevocationService
                        .revokeAllForUser(
                                saved.getId(),
                                RefreshTokenRevocationReason
                                        .USER_DISABLED
                        );
            }

            publishSecurityStateChanged(
                    saved.getId()
            );
        }

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_ENABLED_CHANGED,
                Map.of(
                        "targetUserId",
                        saved.getId().toString(),
                        "targetUserEmail",
                        saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization()
                                .getId()
                                .toString(),
                        "oldEnabled",
                        oldEnabled,
                        "newEnabled",
                        saved.isEnabled(),
                        "changed",
                        changed
                )
        );

        return toResponse(saved);
    }

    @Transactional
    public UserResponse updateRoles(
            UUID id,
            UpdateUserRolesRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        UserEntity user =
                findUserVisibleForCurrentUser(
                        id,
                        currentUser
                );

        rejectSuperAdminMutation(user);

        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException(
                    "Нельзя менять собственные роли"
            );
        }

        rejectAdminManagingAdmin(
                user,
                currentUser
        );

        Set<String> oldRoles =
                UserRoleMapper.toRoleNames(user);

        Set<String> requestedRoles =
                normalizeRoles(request.roles());

        if (requestedRoles.isEmpty()) {
            throw new ConflictException(
                    "У пользователя должна быть хотя бы одна роль"
            );
        }

        boolean removesAdminRole =
                oldRoles.contains(ROLE_ADMIN)
                        && !requestedRoles.contains(ROLE_ADMIN);

        if (user.isEnabled() && removesAdminRole) {
            List<UserEntity> enabledAdmins =
                    userRepository
                            .findEnabledAdminsForUpdate(
                                    user.getOrganization()
                                            .getId()
                            );

            if (enabledAdmins.size() <= 1) {
                throw new ForbiddenOperationException(
                        "Нельзя снять роль ADMIN с последнего "
                                + "активного администратора организации"
                );
            }
        }

        Set<RoleEntity> roles =
                resolveUserManagementRoles(
                        requestedRoles,
                        currentUser
                );

        boolean changed =
                !oldRoles.equals(requestedRoles);

        if (changed) {
            user.setRoles(new HashSet<>(roles));
            user.setTokenVersion(
                    Math.addExact(
                            user.getTokenVersion(),
                            1L
                    )
            );
        }

        UserEntity saved =
                userRepository.save(user);

        if (changed) {
            userSessionRevocationService
                    .revokeAllForUser(
                            saved.getId(),
                            RefreshTokenRevocationReason
                                    .ROLE_CHANGED
                    );

            publishSecurityStateChanged(
                    saved.getId()
            );
        }

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_ROLES_CHANGED,
                Map.of(
                        "targetUserId",
                        saved.getId().toString(),
                        "targetUserEmail",
                        saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization()
                                .getId()
                                .toString(),
                        "oldRoles",
                        oldRoles,
                        "newRoles",
                        UserRoleMapper.toRoleNames(saved),
                        "changed",
                        changed
                )
        );

        return toResponse(saved);
    }

    @Transactional
    public void resetPassword(
            UUID id,
            ResetUserPasswordRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        UserEntity user =
                findUserVisibleForCurrentUser(
                        id,
                        currentUser
                );

        rejectSuperAdminMutation(user);
        rejectAdminManagingAdmin(
                user,
                currentUser
        );

        user.setPasswordHash(
                passwordEncoder.encode(request.password())
        );

        user.setTokenVersion(
                Math.addExact(
                        user.getTokenVersion(),
                        1L
                )
        );

        UserEntity saved =
                userRepository.save(user);

        userSessionRevocationService
                .revokeAllForUser(
                        saved.getId(),
                        RefreshTokenRevocationReason
                                .PASSWORD_RESET
                );

        publishSecurityStateChanged(
                saved.getId()
        );

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_PASSWORD_RESET,
                Map.of(
                        "targetUserId",
                        saved.getId().toString(),
                        "targetUserEmail",
                        saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization()
                                .getId()
                                .toString()
                )
        );
    }

    @Transactional
    public void permanentlyDelete(
            UUID id,
            PermanentDeleteUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (!isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Только SUPER_ADMIN может удалять "
                            + "пользователей навсегда"
            );
        }

        UserEntity user =
                findUserVisibleForCurrentUser(
                        id,
                        currentUser
                );

        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException(
                    "Нельзя удалить собственную учётную запись"
            );
        }

        rejectSuperAdminMutation(user);

        if (platformProperties
                .organizationId()
                .equals(
                        user.getOrganization().getId()
                )) {
            throw new ForbiddenOperationException(
                    "Нельзя удалить пользователя "
                            + "платформенной организации"
            );
        }

        String confirmationEmail =
                normalizeEmail(
                        request.confirmationEmail()
                );

        if (!user.getEmail().equals(confirmationEmail)) {
            throw new BadRequestException(
                    "Email подтверждения не совпадает "
                            + "с email пользователя"
            );
        }

        if (userRepository
                .hasPermanentDeletionDependencies(
                        user.getId()
                )) {
            throw new ConflictException(
                    "Пользователя нельзя удалить навсегда, "
                            + "поскольку у него есть история чатов. "
                            + "Отключите пользователя."
            );
        }

        UUID targetUserId = user.getId();

        UUID targetOrganizationId =
                user.getOrganization().getId();

        String targetEmail = user.getEmail();
        String targetFullName = user.getFullName();

        Set<String> targetRoles =
                UserRoleMapper.toRoleNames(user);

        userSessionRevocationService
                .revokeAllForUser(
                        targetUserId,
                        RefreshTokenRevocationReason.ADMIN_REVOKED
                );

        userRepository.delete(user);
        userRepository.flush();

        publishSecurityStateChanged(
                targetUserId
        );

        auditEventService.record(
                currentUser.getId(),
                targetOrganizationId,
                AuditEventType.USER_PERMANENTLY_DELETED,
                Map.of(
                        "targetUserId",
                        targetUserId.toString(),
                        "targetUserEmail",
                        targetEmail,
                        "targetUserFullName",
                        targetFullName == null
                                ? ""
                                : targetFullName,
                        "targetOrganizationId",
                        targetOrganizationId.toString(),
                        "targetRoles",
                        targetRoles,
                        "hadChatHistory",
                        false,
                        "sessionsRevoked",
                        true
                )
        );
    }

    private UUID resolveTargetOrganizationId(
            UUID requestedOrganizationId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                requestedOrganizationId,
                "requestedOrganizationId не должен быть null"
        );

        if (isSuperAdmin(currentUser)) {
            return requestedOrganizationId;
        }

        if (!currentUser
                .getOrganizationId()
                .equals(requestedOrganizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя создавать пользователя "
                            + "в другой организации"
            );
        }

        return currentUser.getOrganizationId();
    }

    private UserEntity findUserVisibleForCurrentUser(
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

        if (isSuperAdmin(currentUser)) {
            return userRepository
                    .findByIdWithRolesAndOrganization(id)
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Пользователь не найден: "
                                            + id
                            )
                    );
        }

        return userRepository
                .findByIdAndOrganizationId(
                        id,
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Пользователь не найден: "
                                        + id
                        )
                );
    }

    private Set<RoleEntity> resolveUserManagementRoles(
            Set<String> requestedRoles,
            SafeAiUserPrincipal currentUser
    ) {
        Set<String> normalizedRoles =
                normalizeRoles(requestedRoles);

        if (normalizedRoles.isEmpty()) {
            throw new ConflictException(
                    "У пользователя должна быть хотя бы одна роль"
            );
        }

        if (!SYSTEM_ROLES.containsAll(normalizedRoles)) {
            throw new ConflictException(
                    "Неизвестные роли: "
                            + normalizedRoles
            );
        }

        Set<String> assignableRoles =
                isSuperAdmin(currentUser)
                        ? SUPER_ADMIN_ASSIGNABLE_ROLES
                        : ADMIN_ASSIGNABLE_ROLES;

        if (!assignableRoles.containsAll(normalizedRoles)) {
            throw new ForbiddenOperationException(
                    isSuperAdmin(currentUser)
                            ? "SUPER_ADMIN нельзя назначать через "
                            + "user-management endpoint"
                            : "ADMIN может назначать только роль USER"
            );
        }

        return normalizedRoles.stream()
                .map(roleName ->
                        roleRepository.findByName(roleName)
                                .orElseThrow(() ->
                                        new ResourceNotFoundException(
                                                "Роль не найдена: "
                                                        + roleName
                                        )
                                )
                )
                .collect(Collectors.toUnmodifiableSet());
    }

    private void rejectAdminManagingAdmin(
            UserEntity targetUser,
            SafeAiUserPrincipal currentUser
    ) {
        if (!isSuperAdmin(currentUser)
                && hasAdminRole(targetUser)) {
            throw new ForbiddenOperationException(
                    "ADMIN не может управлять другим ADMIN"
            );
        }
    }

    private void rejectPlatformOrganizationCreation(
            UUID targetOrganizationId
    ) {
        if (platformProperties
                .organizationId()
                .equals(targetOrganizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя создавать пользователей "
                            + "в platform organization через обычный "
                            + "user-management endpoint"
            );
        }
    }

    private String normalizeListRole(
            String role
    ) {
        if (role == null || role.isBlank()) {
            return null;
        }

        String normalized = role
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!LIST_FILTER_ROLES.contains(normalized)) {
            throw new BadRequestException(
                    "Недопустимый фильтр роли: "
                            + role
            );
        }

        return normalized;
    }

    private Set<String> normalizeRoles(
            Set<String> roles
    ) {
        if (roles == null) {
            return Set.of();
        }

        return roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(role ->
                        role.toUpperCase(Locale.ROOT)
                )
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isSuperAdmin(
            SafeAiUserPrincipal currentUser
    ) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }

    private boolean isEnabledAdmin(
            UserEntity user
    ) {
        return user.isEnabled()
                && hasAdminRole(user);
    }

    private boolean hasAdminRole(
            UserEntity user
    ) {
        return user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .anyMatch(
                        ROLE_ADMIN::equalsIgnoreCase
                );
    }

    private boolean hasSuperAdminRole(
            UserEntity user
    ) {
        return user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .anyMatch(
                        ROLE_SUPER_ADMIN::equalsIgnoreCase
                );
    }

    private void rejectSuperAdminMutation(
            UserEntity user
    ) {
        if (hasSuperAdminRole(user)) {
            throw new ForbiddenOperationException(
                    "SUPER_ADMIN нельзя изменять через обычный "
                            + "user-management endpoint"
            );
        }
    }

    private void publishSecurityStateChanged(
            UUID userId
    ) {
        eventPublisher.publishEvent(
                new UserSecurityStateChangedEvent(userId)
        );
    }

    private UserResponse toResponse(
            UserEntity entity
    ) {
        return new UserResponse(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getEmail(),
                entity.getFullName(),
                entity.isEnabled(),
                UserRoleMapper.toRoleNames(entity),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastLoginAt()
        );
    }

    private UserDetailsResponse toDetailsResponse(
            UserEntity entity
    ) {
        return new UserDetailsResponse(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getEmail(),
                entity.getFullName(),
                entity.isEnabled(),
                UserRoleMapper.toRoleNames(entity),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastLoginAt()
        );
    }

    private void validatePageableSort(
            Pageable pageable
    ) {
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORT_PROPERTIES.contains(
                    order.getProperty()
            )) {
                throw new BadRequestException(
                        "Сортировка по полю не разрешена: "
                                + order.getProperty()
                );
            }
        }
    }

    private String normalizeEmail(
            String email
    ) {
        Objects.requireNonNull(
                email,
                "email не должен быть null"
        );

        String normalized = email
                .trim()
                .toLowerCase(Locale.ROOT);

        if (normalized.isBlank()) {
            throw new BadRequestException(
                    "Email не должен быть пустым"
            );
        }

        return normalized;
    }

    private String normalizeFullName(
            String fullName
    ) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }

        return fullName
                .trim()
                .replaceAll("\\s+", " ");
    }
}