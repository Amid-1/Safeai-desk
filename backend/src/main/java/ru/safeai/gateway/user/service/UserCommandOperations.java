package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.persistence.DatabaseConstraintClassifier;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.PermanentDeleteUserRequest;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.event.UserSecurityStateChangedEvent;
import ru.safeai.gateway.user.mapper.UserRoleMapper;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.validation.UserEmailNormalizer;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class UserCommandOperations {

    private static final Set<String> EMAIL_UNIQUE_CONSTRAINTS =
            Set.of(
                    "uq_users_email",
                    "ux_users_email_normalized"
            );

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventService auditEventService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserSessionRevocationService userSessionRevocationService;
    private final UserManagementProperties userManagementProperties;
    private final EntityManager entityManager;
    private final Clock clock;
    private final UserAccessPolicy accessPolicy;
    private final UserEntityAccess entityAccess;
    private final UserRoleAssignmentResolver roleResolver;
    private final UserResponseMapper responseMapper;

    UserCommandOperations(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditEventService auditEventService,
            ApplicationEventPublisher eventPublisher,
            UserSessionRevocationService userSessionRevocationService,
            UserManagementProperties userManagementProperties,
            EntityManager entityManager,
            Clock clock,
            UserAccessPolicy accessPolicy,
            UserEntityAccess entityAccess,
            UserRoleAssignmentResolver roleResolver,
            UserResponseMapper responseMapper
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditEventService = auditEventService;
        this.eventPublisher = eventPublisher;
        this.userSessionRevocationService = userSessionRevocationService;
        this.userManagementProperties = userManagementProperties;
        this.entityManager = entityManager;
        this.clock = clock;
        this.accessPolicy = accessPolicy;
        this.entityAccess = entityAccess;
        this.roleResolver = roleResolver;
        this.responseMapper = responseMapper;
    }

    UserResponse create(
            CreateUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        accessPolicy.requireUserManager(currentUser);

        String email = UserEmailNormalizer.normalizeAndValidate(
                request.email()
        );
        String fullName = accessPolicy.normalizeFullName(
                request.fullName()
        );
        String password = accessPolicy.requireValidNewPassword(
                request.password()
        );

        UUID targetOrganizationId =
                accessPolicy.resolveTargetOrganizationId(
                        request.organizationId(),
                        currentUser
                );
        accessPolicy.rejectPlatformOrganizationCreation(
                targetOrganizationId
        );

        /* Fast preflight only; PostgreSQL UNIQUE remains correctness boundary. */
        if (userRepository.existsByEmail(email)) {
            throw duplicateEmail(email);
        }

        /* BCrypt runs before the organization pessimistic lock. */
        String passwordHash = passwordEncoder.encode(password);

        OrganizationEntity organization =
                entityAccess.lockOrganization(targetOrganizationId);

        if (!organization.isEnabled()) {
            throw new ForbiddenOperationException(
                    "Нельзя создать пользователя в отключенной организации"
            );
        }

        Set<RoleEntity> roles =
                roleResolver.resolve(request.roles(), currentUser);

        UserEntity entity = new UserEntity();
        entity.setOrganization(organization);
        entity.setEmail(email);
        entity.setPasswordHash(passwordHash);
        entity.setFullName(fullName);
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
                        "targetUserId",
                        saved.getId().toString(),
                        "targetUserEmail",
                        saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization().getId().toString(),
                        "roles",
                        UserRoleMapper.toRoleNames(saved)
                )
        );

        return responseMapper.toResponse(saved);
    }

    UserResponse updateUser(
            UUID id,
            UpdateUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        accessPolicy.requireUserManager(currentUser);

        UserEntity user =
                entityAccess.findForSecurityMutation(id, currentUser);
        accessPolicy.requireExpectedVersion(
                user,
                request.expectedVersion()
        );
        accessPolicy.rejectSuperAdminMutation(user);
        accessPolicy.rejectSelfManagement(
                user,
                currentUser,
                "редактировать"
        );
        accessPolicy.rejectAdminManagingAdmin(user, currentUser);

        String normalizedEmail =
                UserEmailNormalizer.normalizeAndValidate(request.email());
        String normalizedFullName =
                accessPolicy.normalizeFullName(request.fullName());

        boolean emailChanged =
                !user.getEmail().equals(normalizedEmail);
        boolean fullNameChanged =
                !Objects.equals(
                        user.getFullName(),
                        normalizedFullName
                );

        if (!emailChanged && !fullNameChanged) {
            return responseMapper.toResponse(user);
        }

        if (emailChanged
                && userRepository.existsByEmail(normalizedEmail)) {
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
                        "targetUserId",
                        saved.getId().toString(),
                        "oldEmail",
                        oldEmail,
                        "newEmail",
                        saved.getEmail(),
                        "oldFullName",
                        nullToEmpty(oldFullName),
                        "newFullName",
                        nullToEmpty(saved.getFullName()),
                        "emailChanged",
                        emailChanged,
                        "fullNameChanged",
                        fullNameChanged,
                        "sessionsRevoked",
                        emailChanged
                )
        );

        return responseMapper.toResponse(saved);
    }

    UserResponse updateEnabled(
            UUID id,
            UpdateUserEnabledRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        accessPolicy.requireUserManager(currentUser);

        UserEntity user =
                entityAccess.findForSecurityMutation(id, currentUser);
        accessPolicy.requireExpectedVersion(
                user,
                request.expectedVersion()
        );
        accessPolicy.rejectSuperAdminMutation(user);

        boolean oldEnabled = user.isEnabled();
        boolean newEnabled = Boolean.TRUE.equals(request.enabled());

        if (user.getId().equals(currentUser.getId())
                && !newEnabled) {
            throw new ForbiddenOperationException(
                    "Нельзя отключить самого себя"
            );
        }

        accessPolicy.rejectAdminManagingAdmin(user, currentUser);

        if (!newEnabled && accessPolicy.isEnabledAdmin(user)) {
            entityAccess.assertNotLastEnabledAdmin(user);
        }

        if (oldEnabled == newEnabled) {
            return responseMapper.toResponse(user);
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
                        "targetUserId",
                        saved.getId().toString(),
                        "targetUserEmail",
                        saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization().getId().toString(),
                        "oldEnabled",
                        oldEnabled,
                        "newEnabled",
                        saved.isEnabled(),
                        "changed",
                        true,
                        "sessionsRevoked",
                        !newEnabled
                )
        );

        return responseMapper.toResponse(saved);
    }

    UserResponse updateRoles(
            UUID id,
            UpdateUserRolesRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        accessPolicy.requireUserManager(currentUser);

        UserEntity user =
                entityAccess.findForSecurityMutation(id, currentUser);
        accessPolicy.requireExpectedVersion(
                user,
                request.expectedVersion()
        );
        accessPolicy.rejectSuperAdminMutation(user);
        accessPolicy.rejectSelfManagement(
                user,
                currentUser,
                "менять собственные роли"
        );
        accessPolicy.rejectAdminManagingAdmin(user, currentUser);

        Set<String> oldRoles = UserRoleMapper.toRoleNames(user);
        accessPolicy.requireExactlyOneRequestedRole(request.roles());

        Set<SystemRole> requestedRoles =
                accessPolicy.normalizeRoles(request.roles());
        if (requestedRoles.size() != 1) {
            throw new ConflictException(
                    "У пользователя должна быть ровно одна роль"
            );
        }

        boolean removesAdminRole =
                oldRoles.contains(SystemRole.ADMIN.roleName())
                        && !requestedRoles.contains(SystemRole.ADMIN);

        if (user.isEnabled() && removesAdminRole) {
            entityAccess.assertNotLastEnabledAdmin(user);
        }

        Set<String> requestedRoleNames = requestedRoles.stream()
                .map(SystemRole::roleName)
                .collect(Collectors.toUnmodifiableSet());

        Set<RoleEntity> roles =
                roleResolver.resolve(requestedRoleNames, currentUser);

        if (oldRoles.equals(requestedRoleNames)) {
            return responseMapper.toResponse(user);
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
                        "targetUserId",
                        saved.getId().toString(),
                        "targetUserEmail",
                        saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization().getId().toString(),
                        "oldRoles",
                        oldRoles,
                        "newRoles",
                        UserRoleMapper.toRoleNames(saved),
                        "changed",
                        true,
                        "sessionsRevoked",
                        true
                )
        );

        return responseMapper.toResponse(saved);
    }

    void resetPassword(
            UUID id,
            ResetUserPasswordRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        accessPolicy.requireUserManager(currentUser);

        String password =
                accessPolicy.requireValidNewPassword(request.password());

        UserEntity user =
                entityAccess.findForSecurityMutation(id, currentUser);
        accessPolicy.requireExpectedVersion(
                user,
                request.expectedVersion()
        );
        accessPolicy.rejectSuperAdminMutation(user);
        accessPolicy.rejectAdminManagingAdmin(user, currentUser);

        user.setPasswordHash(passwordEncoder.encode(password));
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
                        "targetUserId",
                        saved.getId().toString(),
                        "targetUserEmail",
                        saved.getEmail(),
                        "targetOrganizationId",
                        saved.getOrganization().getId().toString(),
                        "sessionsRevoked",
                        true
                )
        );
    }

    void permanentlyDelete(
            UUID id,
            PermanentDeleteUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (!accessPolicy.isSuperAdmin(currentUser)) {
            throw new ForbiddenOperationException(
                    "Только SUPER_ADMIN может удалять пользователей навсегда"
            );
        }

        UserEntity user =
                entityAccess.findForSecurityMutation(id, currentUser);
        accessPolicy.requireExpectedVersion(
                user,
                request.expectedVersion()
        );

        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException(
                    "Нельзя удалить собственную учётную запись"
            );
        }

        accessPolicy.rejectSuperAdminMutation(user);

        UUID targetOrganizationId = user.getOrganization().getId();
        if (accessPolicy.isPlatformOrganization(targetOrganizationId)) {
            throw new ForbiddenOperationException(
                    "Нельзя удалить пользователя платформенной организации"
            );
        }

        String confirmationEmail =
                UserEmailNormalizer.normalizeAndValidate(
                        request.confirmationEmail()
                );

        if (!user.getEmail().equals(confirmationEmail)) {
            throw new BadRequestException(
                    "Email подтверждения не совпадает с email пользователя"
            );
        }

        if (accessPolicy.isEnabledAdmin(user)) {
            entityAccess.assertNotLastEnabledAdmin(user);
        }

        if (user.isEnabled()) {
            throw new ForbiddenOperationException(
                    "Перед окончательным удалением пользователя "
                            + "необходимо отключить"
            );
        }

        Instant disabledAt = user.getDisabledAt();
        if (disabledAt == null) {
            throw new ConflictException(
                    "Для отключённого пользователя "
                            + "не зафиксировано время отключения"
            );
        }

        Instant deletionAllowedAt = disabledAt.plus(
                userManagementProperties.permanentDeletionRetention()
        );

        if (clock.instant().isBefore(deletionAllowedAt)) {
            throw new ForbiddenOperationException(
                    "Период хранения перед окончательным "
                            + "удалением ещё не истёк: "
                            + deletionAllowedAt
            );
        }

        if (userRepository.hasActiveRefreshTokens(user.getId())) {
            throw new ConflictException(
                    "Пользователя нельзя удалить: "
                            + "остались активные refresh-сессии"
            );
        }

        if (userRepository.hasPermanentDeletionDependencies(user.getId())) {
            throw new ConflictException(
                    "Пользователя нельзя удалить навсегда: "
                            + "сохранены связанные данные"
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
            if (DatabaseConstraintClassifier
                    .isForeignKeyViolation(exception)) {
                throw new ConflictException(
                        "Пользователя нельзя удалить: "
                                + "во время удаления появились "
                                + "связанные данные"
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
                        "targetUserId",
                        targetUserId.toString(),
                        "targetUserEmail",
                        targetEmail,
                        "targetUserFullName",
                        nullToEmpty(targetFullName),
                        "targetOrganizationId",
                        targetOrganizationId.toString(),
                        "targetRoles",
                        targetRoles,
                        "retentionSatisfied",
                        true,
                        "sessionsRevoked",
                        true
                )
        );
    }

    private UserEntity saveAndRefresh(
            UserEntity entity
    ) {
        UserEntity saved = userRepository.saveAndFlush(entity);
        entityManager.refresh(saved);
        Hibernate.initialize(saved.getOrganization());
        Hibernate.initialize(saved.getRoles());
        return saved;
    }

    private static void incrementTokenVersion(
            UserEntity user
    ) {
        user.setTokenVersion(
                Math.addExact(user.getTokenVersion(), 1L)
        );
    }

    private void publishSecurityStateChanged(
            UUID userId
    ) {
        eventPublisher.publishEvent(
                new UserSecurityStateChangedEvent(userId)
        );
    }

    private static boolean isEmailUniqueViolation(
            Throwable exception
    ) {
        return DatabaseConstraintClassifier.isUniqueViolation(
                exception,
                EMAIL_UNIQUE_CONSTRAINTS.toArray(String[]::new)
        );
    }

    private static ConflictException duplicateEmail(
            String email
    ) {
        return new ConflictException(
                "Пользователь с таким email уже существует: " + email
        );
    }

    private static String nullToEmpty(
            String value
    ) {
        return value == null ? "" : value;
    }
}
