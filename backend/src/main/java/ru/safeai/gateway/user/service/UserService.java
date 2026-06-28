package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.event.UserSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;

import java.util.*;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private static final Set<String> SYSTEM_ROLES =
            Set.of(ROLE_USER, ROLE_ADMIN, ROLE_SUPER_ADMIN);

    /**
     * В обычном user-management endpoint не даём назначать SUPER_ADMIN.
     * SUPER_ADMIN лучше создавать через seed/Flyway или отдельный platform-admin endpoint.
     */
    private static final Set<String> USER_MANAGEMENT_ASSIGNABLE_ROLES =
            Set.of(ROLE_USER, ROLE_ADMIN);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventService auditEventService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public UserResponse create(CreateUserRequest request, SafeAiUserPrincipal currentUser) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        String email = normalizeEmail(request.email());

        UUID targetOrganizationId = resolveTargetOrganizationId(
                request.organizationId(),
                currentUser
        );

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Пользователь с таким email уже существует: " + email);
        }

        OrganizationEntity organization = organizationRepository.findById(targetOrganizationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + targetOrganizationId
                ));

        Set<String> requestedRoles = request.roles() == null || request.roles().isEmpty()
                ? Set.of(ROLE_USER)
                : request.roles();

        Set<RoleEntity> roles = resolveUserManagementRoles(requestedRoles);

        UserEntity entity = new UserEntity();
        entity.setOrganization(organization);
        entity.setEmail(email);
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setFullName(normalizeFullName(request.fullName()));
        entity.setEnabled(true);
        entity.setRoles(roles);

        UserEntity saved;

        try {
            saved = userRepository.save(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    "Пользователь с таким email уже существует: " + email
            );
        }

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_CREATED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "targetOrganizationId", saved.getOrganization().getId().toString(),
                        "roles", roleNames(saved)
                )
        );

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> findAll(
            SafeAiUserPrincipal currentUser,
            Pageable pageable
    ) {
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        Objects.requireNonNull(pageable, "pageable не должен быть null");

        if (isSuperAdmin(currentUser)) {
            return userRepository.findAllWithRolesAndOrganization(pageable)
                    .map(this::toResponse);
        }

        return userRepository.findAllByOrganizationIdWithRoles(
                        currentUser.getOrganizationId(),
                        pageable
                )
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse findById(UUID id, SafeAiUserPrincipal currentUser) {
        return toResponse(findUserVisibleForCurrentUser(id, currentUser));
    }

    @Transactional
    public UserResponse updateEnabled(
            UUID id,
            UpdateUserEnabledRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        UserEntity user = findUserVisibleForCurrentUser(id, currentUser);
        rejectSuperAdminMutation(user);

        boolean oldEnabledValue = user.isEnabled();
        boolean newEnabledValue = Boolean.TRUE.equals(request.enabled());

        if (user.getId().equals(currentUser.getId()) && !newEnabledValue) {
            throw new ForbiddenOperationException("Нельзя отключить самого себя");
        }

        if (!newEnabledValue && isEnabledAdmin(user)) {
            List<UserEntity> enabledAdmins =
                    userRepository.findEnabledAdminsForUpdate(user.getOrganization().getId());

            if (enabledAdmins.size() <= 1) {
                throw new ForbiddenOperationException(
                        "Нельзя отключить последнего активного администратора организации"
                );
            }
        }

        boolean changed = oldEnabledValue != newEnabledValue;

        user.setEnabled(newEnabledValue);

        if (changed) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }

        UserEntity saved = userRepository.save(user);

        if (changed) {
            eventPublisher.publishEvent(new UserSecurityStateChangedEvent(saved.getId()));
        }

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_ENABLED_CHANGED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "targetOrganizationId", saved.getOrganization().getId().toString(),
                        "oldEnabled", oldEnabledValue,
                        "newEnabled", saved.isEnabled()
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
        UserEntity user = findUserVisibleForCurrentUser(id, currentUser);
        rejectSuperAdminMutation(user);

        if (user.getId().equals(currentUser.getId())) {
            throw new ForbiddenOperationException("Нельзя менять собственные роли");
        }

        Set<String> oldRoles = roleNames(user);
        Set<String> requestedRoles = normalizeRoles(request.roles());

        if (requestedRoles.isEmpty()) {
            throw new ConflictException("У пользователя должна быть хотя бы одна роль");
        }

        boolean removesAdminRole = hasAdminRole(user) && !requestedRoles.contains(ROLE_ADMIN);

        if (user.isEnabled() && removesAdminRole) {
            List<UserEntity> enabledAdmins =
                    userRepository.findEnabledAdminsForUpdate(user.getOrganization().getId());

            if (enabledAdmins.size() <= 1) {
                throw new ForbiddenOperationException(
                        "Нельзя снять роль ADMIN с последнего активного администратора организации"
                );
            }
        }

        Set<RoleEntity> roles = resolveUserManagementRoles(requestedRoles);

        user.setRoles(roles);
        user.setTokenVersion(user.getTokenVersion() + 1);

        UserEntity saved = userRepository.save(user);

        eventPublisher.publishEvent(new UserSecurityStateChangedEvent(saved.getId()));

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_ROLES_CHANGED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "targetOrganizationId", saved.getOrganization().getId().toString(),
                        "oldRoles", oldRoles,
                        "newRoles", roleNames(saved)
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
        UserEntity user = findUserVisibleForCurrentUser(id, currentUser);
        rejectSuperAdminMutation(user);

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setTokenVersion(user.getTokenVersion() + 1);

        UserEntity saved = userRepository.save(user);

        eventPublisher.publishEvent(new UserSecurityStateChangedEvent(saved.getId()));

        auditEventService.record(
                currentUser.getId(),
                saved.getOrganization().getId(),
                AuditEventType.USER_PASSWORD_RESET,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "targetOrganizationId", saved.getOrganization().getId().toString()
                )
        );
    }

    private UUID resolveTargetOrganizationId(
            UUID requestedOrganizationId,
            SafeAiUserPrincipal currentUser
    ) {
        if (isSuperAdmin(currentUser)) {
            return requestedOrganizationId;
        }

        if (!currentUser.getOrganizationId().equals(requestedOrganizationId)) {
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
        if (isSuperAdmin(currentUser)) {
            return userRepository.findByIdWithRolesAndOrganization(id)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Пользователь не найден: " + id
                    ));
        }

        return userRepository.findByIdAndOrganizationId(id, currentUser.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + id
                ));
    }

    private Set<RoleEntity> resolveUserManagementRoles(Set<String> requestedRoles) {
        Set<String> normalizedRoles = normalizeRoles(requestedRoles);

        if (normalizedRoles.isEmpty()) {
            throw new ConflictException("У пользователя должна быть хотя бы одна роль");
        }

        if (!SYSTEM_ROLES.containsAll(normalizedRoles)) {
            throw new ConflictException("Неизвестные роли: " + normalizedRoles);
        }

        if (!USER_MANAGEMENT_ASSIGNABLE_ROLES.containsAll(normalizedRoles)) {
            throw new ForbiddenOperationException(
                    "Недопустимые роли для назначения через user-management endpoint: "
                            + normalizedRoles
            );
        }

        return normalizedRoles.stream()
                .map(roleName -> roleRepository.findByName(roleName)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Роль не найдена: " + roleName
                        )))
                .collect(Collectors.toSet());
    }

    private Set<String> normalizeRoles(Set<String> roles) {
        if (roles == null) {
            return Set.of();
        }

        return roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private boolean isSuperAdmin(SafeAiUserPrincipal currentUser) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }

    private boolean isEnabledAdmin(UserEntity user) {
        return user.isEnabled() && hasAdminRole(user);
    }

    private boolean hasAdminRole(UserEntity user) {
        return user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .anyMatch(ROLE_ADMIN::equalsIgnoreCase);
    }

    private boolean hasSuperAdminRole(UserEntity user) {
        return user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .anyMatch(ROLE_SUPER_ADMIN::equalsIgnoreCase);
    }

    private void rejectSuperAdminMutation(UserEntity user) {
        if (hasSuperAdminRole(user)) {
            throw new ForbiddenOperationException(
                    "SUPER_ADMIN нельзя изменять через обычный user-management endpoint"
            );
        }
    }

    private Set<String> roleNames(UserEntity entity) {
        return entity.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private UserResponse toResponse(UserEntity entity) {
        return new UserResponse(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getEmail(),
                entity.getFullName(),
                entity.isEnabled(),
                roleNames(entity),
                entity.getCreatedAt()
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String normalizeFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }

        return fullName.trim().replaceAll("\\s+", " ");
    }
}