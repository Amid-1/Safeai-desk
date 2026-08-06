package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.Hibernate;
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
import ru.safeai.gateway.common.exception.UserVersionConflictException;
import ru.safeai.gateway.common.persistence.DatabaseConstraintClassifier;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
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

import java.time.Clock;
import java.time.Instant;
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
@EnableConfigurationProperties(UserManagementProperties.class)
public class UserService {

    private static final Set<SystemRole> SUPER_ADMIN_ASSIGNABLE_ROLES =
            Set.of(SystemRole.USER, SystemRole.ADMIN);

    private static final Set<SystemRole> ADMIN_ASSIGNABLE_ROLES =
            Set.of(SystemRole.USER);

    private static final Set<SystemRole> LIST_FILTER_ROLES =
            Set.of(SystemRole.USER, SystemRole.ADMIN);

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of(
            "createdAt",
            "email",
            "fullName",
            "enabled",
            "lastLoginAt"
    );

    private static final Set<String> EMAIL_UNIQUE_CONSTRAINTS = Set.of(
            "uq_users_email",
            "ux_users_email_normalized"
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventService auditEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserSessionRevocationService userSessionRevocationService;
    private final PlatformProperties platformProperties;
    private final UserManagementProperties userManagementProperties;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional
    public UserResponse create(
            CreateUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        String email = normalizeEmail(request.email());

        UUID targetOrganizationId = resolveTargetOrganizationId(
                request.organizationId(),
                currentUser
        );

        rejectPlatformOrganizationCreation(targetOrganizationId);

        OrganizationEntity organization = lockOrganization(
                targetOrganizationId
        );

        if (!organization.isEnabled()) {
            throw new ForbiddenOperationException(
                    "Нельзя создать пользователя в отключенной организации"
            );
        }

        if (userRepository.existsByEmail(email)) {
            throw duplicateEmail(email);
        }

        /*
         * Роль не выводится из контекста и не подставляется молча.
         * API-контракт требует ровно одну роль, а service повторно
         * проверяет инвариант для прямых вызовов вне MVC.
         */
        Set<RoleEntity> roles = resolveUserManagementRoles(
                request.roles(),
                currentUser
        );

        UserEntity entity = new UserEntity();
        entity.setOrganization(organization);
        entity.setEmail(email);
        entity.setPasswordHash(
                passwordEncoder.encode(request.password())
        );
        entity.setFullName(normalizeFullName(request.fullName()));
        entity.setEnabled(true);
        entity.setDisabledAt(null);
        entity.setRoles(new HashSet<>(roles));

        UserEntity saved;

        try {
            saved = saveAndRefresh(entity);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw duplicateEmail(email);
            }

            throw exception;
        }

        auditEventService.record(
                currentUser,
                saved.getOrganization().getId(),
                AuditEventType.USER_CREATED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization().getId().toString(),
                        "roles", UserRoleMapper.toRoleNames(saved)
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
        Objects.requireNonNull(pageable, "pageable не должен быть null");

        Pageable stablePageable = normalizePageable(pageable);
        String normalizedRole = normalizeListRole(role);

        Page<UUID> idPage;

        if (isSuperAdmin(currentUser)) {
            idPage = normalizedRole == null
                    ? userRepository.findAllIds(stablePageable)
                    : userRepository.findAllIdsByRole(
                    normalizedRole,
                    stablePageable
            );
        } else {
            idPage = normalizedRole == null
                    ? userRepository.findAllIdsByOrganizationId(
                    currentUser.getOrganizationId(),
                    stablePageable
            )
                    : userRepository.findAllIdsByOrganizationIdAndRole(
                    currentUser.getOrganizationId(),
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

        List<UserEntity> users = userRepository
                .findAllByIdsWithRolesAndOrganization(
                        idPage.getContent()
                );

        Map<UUID, UserEntity> usersById = new HashMap<>();

        for (UserEntity user : users) {
            usersById.put(user.getId(), user);
        }

        List<UserResponse> content = new ArrayList<>(
                idPage.getNumberOfElements()
        );

        for (UUID userId : idPage.getContent()) {
            UserEntity user = usersById.get(userId);

            if (user == null) {
                throw new IllegalStateException(
                        "Не удалось загрузить пользователя из page snapshot: "
                                + userId
                );
            }

            content.add(toResponse(user));
        }

        return new PageImpl<>(
                content,
                stablePageable,
                idPage.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public UserDetailsResponse findDetailsById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        return toDetailsResponse(
                findUserVisibleForCurrentUser(id, currentUser)
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

    @Transactional
    public UserResponse updateUser(
            UUID id,
            UpdateUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");

        UserEntity user = findUserForSecurityMutation(id, currentUser);

        requireExpectedVersion(user, request.expectedVersion());

        rejectSuperAdminMutation(user);
        rejectSelfManagement(user, currentUser, "редактировать");
        rejectAdminManagingAdmin(user, currentUser);

        String normalizedEmail = normalizeEmail(request.email());
        String normalizedFullName = normalizeFullName(request.fullName());

        boolean emailChanged = !user.getEmail().equals(normalizedEmail);
        boolean fullNameChanged = !Objects.equals(
                user.getFullName(),
                normalizedFullName
        );

        if (!emailChanged && !fullNameChanged) {
            return toResponse(user);
        }

        if (emailChanged && userRepository.existsByEmail(normalizedEmail)) {
            throw duplicateEmail(normalizedEmail);
        }

        String oldEmail = user.getEmail();
        String oldFullName = user.getFullName();

        user.setEmail(normalizedEmail);
        user.setFullName(normalizedFullName);

        if (emailChanged) {
            incrementTokenVersion(user);
        }

        UserEntity saved;

        try {
            saved = saveAndRefresh(user);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw duplicateEmail(normalizedEmail);
            }

            throw exception;
        }

        if (emailChanged) {
            userSessionRevocationService.revokeAllForUser(
                    saved.getId(),
                    RefreshTokenRevocationReason.EMAIL_CHANGED
            );
            publishSecurityStateChanged(saved.getId());
        }

        auditEventService.record(
                currentUser,
                saved.getOrganization().getId(),
                AuditEventType.USER_UPDATED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "oldEmail", oldEmail,
                        "newEmail", saved.getEmail(),
                        "oldFullName", nullToEmpty(oldFullName),
                        "newFullName", nullToEmpty(saved.getFullName()),
                        "emailChanged", emailChanged,
                        "fullNameChanged", fullNameChanged,
                        "sessionsRevoked", emailChanged
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
        Objects.requireNonNull(request, "request не должен быть null");

        UserEntity user = findUserForSecurityMutation(id, currentUser);

        requireExpectedVersion(user, request.expectedVersion());

        rejectSuperAdminMutation(user);

        boolean oldEnabled = user.isEnabled();
        boolean newEnabled = Boolean.TRUE.equals(request.enabled());

        if (user.getId().equals(currentUser.getId()) && !newEnabled) {
            throw new ForbiddenOperationException(
                    "Нельзя отключить самого себя"
            );
        }

        rejectAdminManagingAdmin(user, currentUser);

        if (!newEnabled && isEnabledAdmin(user)) {
            assertNotLastEnabledAdmin(user);
        }

        boolean changed = oldEnabled != newEnabled;

        if (!changed) {
            return toResponse(user);
        }

        user.setEnabled(newEnabled);
        user.setDisabledAt(newEnabled ? null : clock.instant());
        incrementTokenVersion(user);

        UserEntity saved = saveAndRefresh(user);

        if (!newEnabled) {
            userSessionRevocationService.revokeAllForUser(
                    saved.getId(),
                    RefreshTokenRevocationReason.USER_DISABLED
            );
        }

        publishSecurityStateChanged(saved.getId());

        auditEventService.record(
                currentUser,
                saved.getOrganization().getId(),
                AuditEventType.USER_ENABLED_CHANGED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization().getId().toString(),
                        "oldEnabled", oldEnabled,
                        "newEnabled", saved.isEnabled(),
                        "changed", true,
                        "sessionsRevoked", !newEnabled
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
        Objects.requireNonNull(request, "request не должен быть null");

        UserEntity user = findUserForSecurityMutation(id, currentUser);

        requireExpectedVersion(user, request.expectedVersion());

        rejectSuperAdminMutation(user);
        rejectSelfManagement(user, currentUser, "менять собственные роли");
        rejectAdminManagingAdmin(user, currentUser);

        Set<String> oldRoles = UserRoleMapper.toRoleNames(user);
        requireExactlyOneRequestedRole(request.roles());
        Set<SystemRole> requestedRoles = normalizeRoles(request.roles());

        if (requestedRoles.size() != 1) {
            throw new ConflictException(
                    "У пользователя должна быть ровно одна роль"
            );
        }

        boolean removesAdminRole = oldRoles.contains(
                SystemRole.ADMIN.roleName()
        ) && !requestedRoles.contains(SystemRole.ADMIN);

        if (user.isEnabled() && removesAdminRole) {
            assertNotLastEnabledAdmin(user);
        }

        Set<RoleEntity> roles = resolveUserManagementRoles(
                requestedRoles.stream()
                        .map(SystemRole::roleName)
                        .collect(Collectors.toUnmodifiableSet()),
                currentUser
        );

        Set<String> requestedRoleNames = requestedRoles.stream()
                .map(SystemRole::roleName)
                .collect(Collectors.toUnmodifiableSet());

        if (oldRoles.equals(requestedRoleNames)) {
            return toResponse(user);
        }

        user.getRoles().clear();
        user.getRoles().addAll(roles);
        incrementTokenVersion(user);

        UserEntity saved = saveAndRefresh(user);

        userSessionRevocationService.revokeAllForUser(
                saved.getId(),
                RefreshTokenRevocationReason.ROLE_CHANGED
        );
        publishSecurityStateChanged(saved.getId());

        auditEventService.record(
                currentUser,
                saved.getOrganization().getId(),
                AuditEventType.USER_ROLES_CHANGED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization().getId().toString(),
                        "oldRoles", oldRoles,
                        "newRoles", UserRoleMapper.toRoleNames(saved),
                        "changed", true,
                        "sessionsRevoked", true
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
        Objects.requireNonNull(request, "request не должен быть null");

        UserEntity user = findUserForSecurityMutation(id, currentUser);

        requireExpectedVersion(user, request.expectedVersion());

        rejectSuperAdminMutation(user);
        rejectAdminManagingAdmin(user, currentUser);

        user.setPasswordHash(
                passwordEncoder.encode(request.password())
        );
        incrementTokenVersion(user);

        UserEntity saved = saveAndRefresh(user);

        userSessionRevocationService.revokeAllForUser(
                saved.getId(),
                RefreshTokenRevocationReason.PASSWORD_RESET
        );
        publishSecurityStateChanged(saved.getId());

        auditEventService.record(
                currentUser,
                saved.getOrganization().getId(),
                AuditEventType.USER_PASSWORD_RESET,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization().getId().toString(),
                        "sessionsRevoked", true
                )
        );
    }

    @Transactional
    public void permanentlyDelete(
            UUID id,
            PermanentDeleteUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (!isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Только SUPER_ADMIN может удалять пользователей навсегда"
            );
        }

        UserEntity user = findUserForSecurityMutation(id, currentUser);

        requireExpectedVersion(user, request.expectedVersion());

        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException(
                    "Нельзя удалить собственную учётную запись"
            );
        }

        rejectSuperAdminMutation(user);

        UUID targetOrganizationId = user.getOrganization().getId();

        if (platformProperties.organizationId().equals(targetOrganizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя удалить пользователя платформенной организации"
            );
        }

        String confirmationEmail = normalizeEmail(
                request.confirmationEmail()
        );

        if (!user.getEmail().equals(confirmationEmail)) {
            throw new BadRequestException(
                    "Email подтверждения не совпадает с email пользователя"
            );
        }

        if (isEnabledAdmin(user)) {
            assertNotLastEnabledAdmin(user);
        }

        if (user.isEnabled()) {
            throw new ForbiddenOperationException(
                    "Перед окончательным удалением пользователя необходимо отключить"
            );
        }

        Instant disabledAt = user.getDisabledAt();

        if (disabledAt == null) {
            throw new ConflictException(
                    "Для отключённого пользователя не зафиксировано время отключения"
            );
        }

        Instant deletionAllowedAt = disabledAt.plus(
                userManagementProperties.permanentDeletionRetention()
        );

        if (clock.instant().isBefore(deletionAllowedAt)) {
            throw new ForbiddenOperationException(
                    "Период хранения перед окончательным удалением ещё не истёк: "
                            + deletionAllowedAt
            );
        }

        if (userRepository.hasActiveRefreshTokens(user.getId())) {
            throw new ConflictException(
                    "Пользователя нельзя удалить: остались активные refresh-сессии"
            );
        }

        if (userRepository.hasPermanentDeletionDependencies(user.getId())) {
            throw new ConflictException(
                    "Пользователя нельзя удалить навсегда: сохранены связанные данные"
            );
        }

        UUID targetUserId = user.getId();
        String targetEmail = user.getEmail();
        String targetFullName = user.getFullName();
        Set<String> targetRoles = UserRoleMapper.toRoleNames(user);

        userSessionRevocationService.revokeAllForUser(
                targetUserId,
                RefreshTokenRevocationReason.ADMIN_REVOKED
        );

        try {
            userRepository.delete(user);
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintClassifier.isForeignKeyViolation(
                    exception
            )) {
                throw new ConflictException(
                        "Пользователя нельзя удалить: во время удаления появились связанные данные"
                );
            }

            throw exception;
        }

        publishSecurityStateChanged(targetUserId);

        auditEventService.record(
                currentUser,
                targetOrganizationId,
                AuditEventType.USER_PERMANENTLY_DELETED,
                Map.of(
                        "targetUserId", targetUserId.toString(),
                        "targetUserEmail", targetEmail,
                        "targetUserFullName", nullToEmpty(targetFullName),
                        "targetOrganizationId",
                        targetOrganizationId.toString(),
                        "targetRoles", targetRoles,
                        "retentionSatisfied", true,
                        "sessionsRevoked", true
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

        if (!currentUser.getOrganizationId().equals(
                requestedOrganizationId
        )) {
            throw new ForbiddenOperationException(
                    "Нельзя создавать пользователя в другой организации"
            );
        }

        return currentUser.getOrganizationId();
    }

    private UserEntity findUserVisibleForCurrentUser(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(id, "id не должен быть null");
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (isSuperAdmin(currentUser)) {
            return userRepository.findByIdWithRolesAndOrganization(id)
                    .orElseThrow(() -> userNotFound(id));
        }

        return userRepository.findByIdAndOrganizationId(
                        id,
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() -> userNotFound(id));
    }

    /**
     * Единый порядок административных mutation:
     * organization lock -> user lock -> invariant checks -> mutation.
     *
     * <p>Предварительный visibility snapshot загружает versioned
     * OrganizationEntity в persistence context. Перед получением
     * PESSIMISTIC_WRITE snapshot и организация выборочно отсоединяются,
     * чтобы Hibernate не пытался повысить блокировку устаревшей версии
     * организации после параллельного изменения organization epoch.</p>
     */
    private UserEntity findUserForSecurityMutation(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        UserEntity visibleSnapshot = findUserVisibleForCurrentUser(
                id,
                currentUser
        );

        OrganizationEntity visibleOrganization =
                visibleSnapshot.getOrganization();

        UUID organizationId = visibleOrganization.getId();

        detachVisibleSecuritySnapshot(
                visibleSnapshot,
                visibleOrganization
        );

        lockOrganization(organizationId);

        UserEntity locked = userRepository.findByIdForSecurityUpdate(id)
                .orElseThrow(() -> userNotFound(id));

        UUID lockedOrganizationId = locked.getOrganization().getId();
        Hibernate.initialize(locked.getRoles());

        if (!organizationId.equals(lockedOrganizationId)) {
            throw new IllegalStateException(
                    "Организация пользователя изменилась во время блокировки: "
                            + id
            );
        }

        if (!isSuperAdmin(currentUser)
                && !currentUser.getOrganizationId().equals(
                lockedOrganizationId
        )) {
            throw userNotFound(id);
        }

        return locked;
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

    private OrganizationEntity lockOrganization(UUID organizationId) {
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

    private Set<RoleEntity> resolveUserManagementRoles(
            Set<String> requestedRoles,
            SafeAiUserPrincipal currentUser
    ) {
        requireExactlyOneRequestedRole(requestedRoles);

        Set<SystemRole> normalizedRoles = normalizeRoles(requestedRoles);

        if (normalizedRoles.size() != 1) {
            throw new ConflictException(
                    "У пользователя должна быть ровно одна роль"
            );
        }

        Set<SystemRole> assignableRoles = isSuperAdmin(currentUser)
                ? SUPER_ADMIN_ASSIGNABLE_ROLES
                : ADMIN_ASSIGNABLE_ROLES;

        if (!assignableRoles.containsAll(normalizedRoles)) {
            throw new ForbiddenOperationException(
                    isSuperAdmin(currentUser)
                            ? "SUPER_ADMIN нельзя назначать через user-management endpoint"
                            : "ADMIN может назначать только роль USER"
            );
        }

        return normalizedRoles.stream()
                .map(role -> roleRepository
                        .findByName(role.roleName())
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Роль не найдена: "
                                                + role.roleName()
                                )
                        )
                )
                .collect(Collectors.toUnmodifiableSet());
    }

    private void rejectAdminManagingAdmin(
            UserEntity targetUser,
            SafeAiUserPrincipal currentUser
    ) {
        if (!isSuperAdmin(currentUser) && hasRole(
                targetUser,
                SystemRole.ADMIN
        )) {
            throw new ForbiddenOperationException(
                    "ADMIN не может управлять другим ADMIN"
            );
        }
    }

    private void rejectPlatformOrganizationCreation(
            UUID targetOrganizationId
    ) {
        if (platformProperties.organizationId().equals(
                targetOrganizationId
        )) {
            throw new ForbiddenOperationException(
                    "Нельзя создавать пользователей в platform organization через обычный user-management endpoint"
            );
        }
    }

    private String normalizeListRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }

        SystemRole normalized;

        try {
            normalized = SystemRole.parse(role);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(
                    "Недопустимый фильтр роли: " + role
            );
        }

        if (!LIST_FILTER_ROLES.contains(normalized)) {
            throw new BadRequestException(
                    "Недопустимый фильтр роли: " + role
            );
        }

        return normalized.roleName();
    }

    private Set<SystemRole> normalizeRoles(Set<String> roles) {
        if (roles == null) {
            return Set.of();
        }

        try {
            return roles.stream()
                    .map(role -> {
                        if (role == null || role.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Пустая системная роль"
                            );
                        }

                        return SystemRole.parse(role.trim());
                    })
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException exception) {
            throw new ConflictException(
                    "Передана неизвестная или пустая системная роль"
            );
        }
    }

    private void requireExactlyOneRequestedRole(
            Set<String> roles
    ) {
        if (roles == null || roles.size() != 1) {
            throw new ConflictException(
                    "У пользователя должна быть ровно одна роль"
            );
        }
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SystemRole.SUPER_ADMIN.authority()::equals);
    }

    private boolean isEnabledAdmin(UserEntity user) {
        return user.isEnabled() && hasRole(user, SystemRole.ADMIN);
    }

    private boolean hasRole(UserEntity user, SystemRole role) {
        return user.getRoles().stream()
                .map(RoleEntity::getName)
                .filter(Objects::nonNull)
                .map(SystemRole::parse)
                .anyMatch(role::equals);
    }

    private void rejectSuperAdminMutation(UserEntity user) {
        if (hasRole(user, SystemRole.SUPER_ADMIN)) {
            throw new ForbiddenOperationException(
                    "SUPER_ADMIN нельзя изменять через обычный user-management endpoint"
            );
        }
    }

    private void rejectSelfManagement(
            UserEntity user,
            SafeAiUserPrincipal currentUser,
            String operation
    ) {
        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException(
                    "Нельзя " + operation + " самого себя через user-management"
            );
        }
    }

    private void assertNotLastEnabledAdmin(UserEntity user) {
        List<UserEntity> enabledAdmins = userRepository
                .findEnabledAdminsForUpdate(
                        user.getOrganization().getId()
                );

        if (enabledAdmins.size() <= 1) {
            throw new ForbiddenOperationException(
                    "Нельзя изменить или удалить последнего активного администратора организации"
            );
        }
    }


    private void requireExpectedVersion(
            UserEntity user,
            Long expectedVersion
    ) {
        if (expectedVersion == null || expectedVersion < 0L) {
            throw new BadRequestException(
                    "expectedVersion должен быть неотрицательным числом"
            );
        }

        if (user.getVersion() != expectedVersion) {
            throw new UserVersionConflictException(
                    user.getId(),
                    expectedVersion,
                    user.getVersion()
            );
        }
    }

    private void incrementTokenVersion(UserEntity user) {
        user.setTokenVersion(
                Math.addExact(user.getTokenVersion(), 1L)
        );
    }

    private void publishSecurityStateChanged(UUID userId) {
        eventPublisher.publishEvent(
                new UserSecurityStateChangedEvent(userId)
        );
    }

    private UserEntity saveAndRefresh(UserEntity entity) {
        UserEntity saved = userRepository.saveAndFlush(entity);
        entityManager.refresh(saved);
        initializeUserAssociations(saved);
        return saved;
    }

    private void initializeUserAssociations(UserEntity user) {
        Hibernate.initialize(user.getOrganization());
        Hibernate.initialize(user.getRoles());
    }

    private UserResponse toResponse(UserEntity entity) {
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

    private UserDetailsResponse toDetailsResponse(UserEntity entity) {
        return new UserDetailsResponse(
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

    private Pageable normalizePageable(Pageable pageable) {
        validatePageableSort(pageable);

        Sort sort = pageable.getSort().isUnsorted()
                ? Sort.by(Sort.Order.desc("createdAt"))
                : pageable.getSort();

        boolean hasId = sort.stream()
                .anyMatch(order -> "id".equals(order.getProperty()));

        if (!hasId) {
            sort = sort.and(Sort.by(Sort.Order.desc("id")));
        }

        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                sort
        );
    }

    private void validatePageableSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException(
                        "Сортировка по полю не разрешена: "
                                + order.getProperty()
                );
            }
        }
    }

    private String normalizeEmail(String email) {
        Objects.requireNonNull(email, "email не должен быть null");

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

    private String normalizeFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }

        return fullName.trim().replaceAll("\\s+", " ");
    }

    private boolean isEmailUniqueViolation(Throwable exception) {
        return DatabaseConstraintClassifier.isUniqueViolation(
                exception,
                EMAIL_UNIQUE_CONSTRAINTS.toArray(String[]::new)
        );
    }

    private ConflictException duplicateEmail(String email) {
        return new ConflictException(
                "Пользователь с таким email уже существует: " + email
        );
    }

    private ResourceNotFoundException userNotFound(UUID id) {
        return new ResourceNotFoundException(
                "Пользователь не найден: " + id
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
