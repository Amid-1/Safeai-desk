package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;

import java.util.Map;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventService auditEventService;
    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");

    @Transactional
    public UserResponse create(CreateUserRequest request, SafeAiUserPrincipal currentUser) {
        String email = request.email().trim().toLowerCase();

        if (!currentUser.getOrganizationId().equals(request.organizationId())) {
            throw new ForbiddenOperationException("Нельзя создавать пользователя в другой организации");
        }

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Пользователь с таким email уже существует: " + email);
        }

        OrganizationEntity organization = organizationRepository.findById(request.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Организация не найдена: " + request.organizationId()
                ));

        Set<String> requestedRoles = request.roles() == null || request.roles().isEmpty()
                ? Set.of("USER")
                : request.roles();

        Set<RoleEntity> roles = resolveRoles(requestedRoles);

        UserEntity entity = new UserEntity();
        entity.setOrganization(organization);
        entity.setEmail(email);
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setFullName(normalizeFullName(request.fullName()));
        entity.setEnabled(true);
        entity.setRoles(roles);

        UserEntity saved = userRepository.save(entity);

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.USER_CREATED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "roles", roleNames(saved)
                )
        );

        return toResponse(saved);
    }

    private Set<String> roleNames(UserEntity entity) {
        return entity.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findAll(SafeAiUserPrincipal currentUser) {
        return userRepository.findAllByOrganizationIdWithRoles(currentUser.getOrganizationId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse findById(UUID id) {
        return toResponse(findUserEntity(id));
    }

    @Transactional
    public UserResponse updateEnabled(
            UUID id,
            UpdateUserEnabledRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        UserEntity user = findUserEntity(id);
        boolean newEnabledValue = Boolean.TRUE.equals(request.enabled());

        if (user.getId().equals(currentUser.getId()) && !newEnabledValue) {
            throw new ForbiddenOperationException("Нельзя отключить самого себя");
        }

        if (!newEnabledValue && isEnabledAdmin(user) && userRepository.countEnabledAdmins() <= 1) {
            throw new ForbiddenOperationException("Нельзя отключить последнего активного администратора");
        }

        user.setEnabled(newEnabledValue);

        if (!newEnabledValue) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }

        UserEntity saved = userRepository.save(user);

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.USER_ENABLED_CHANGED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "enabled", saved.isEnabled()
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
        UserEntity user = findUserEntity(id);

        Set<String> requestedRoles = request.roles()
                .stream()
                .map(role -> role.trim().toUpperCase())
                .collect(Collectors.toSet());

        if (requestedRoles.isEmpty()) {
            throw new ConflictException("У пользователя должна быть хотя бы одна роль");
        }

        boolean removesAdminRole = hasAdminRole(user) && !requestedRoles.contains("ADMIN");

        if (user.getId().equals(currentUser.getId()) && removesAdminRole) {
            throw new ForbiddenOperationException("Нельзя снять роль ADMIN с самого себя");
        }

        if (user.isEnabled() && removesAdminRole && userRepository.countEnabledAdmins() <= 1) {
            throw new ForbiddenOperationException("Нельзя снять роль ADMIN с последнего активного администратора");
        }

        Set<RoleEntity> roles = resolveRoles(requestedRoles);

        user.setRoles(roles);
        user.setTokenVersion(user.getTokenVersion() + 1);

        UserEntity saved = userRepository.save(user);

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.USER_ROLES_CHANGED,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail(),
                        "roles", requestedRoles
                )
        );

        return toResponse(saved);
    }

    @Transactional
    public UserResponse resetPassword(
            UUID id,
            ResetUserPasswordRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        UserEntity user = findUserEntity(id);

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setTokenVersion(user.getTokenVersion() + 1);

        UserEntity saved = userRepository.save(user);

        auditEventService.record(
                currentUser.getId(),
                AuditEventType.USER_PASSWORD_RESET,
                Map.of(
                        "targetUserId", saved.getId().toString(),
                        "targetUserEmail", saved.getEmail()
                )
        );

        return toResponse(saved);
    }

    private UserEntity findUserEntity(UUID id) {
        return userRepository.findByIdWithRolesAndOrganization(id)
                .orElseThrow(() -> new ResourceNotFoundException("Пользователь не найден: " + id));
    }

    private Set<RoleEntity> resolveRoles(Set<String> requestedRoles) {
        Set<String> normalizedRoles = requestedRoles.stream()
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        if (normalizedRoles.isEmpty()) {
            throw new ConflictException("У пользователя должна быть хотя бы одна роль");
        }

        if (!ALLOWED_ROLES.containsAll(normalizedRoles)) {
            throw new ConflictException("Недопустимые роли: " + normalizedRoles);
        }

        return normalizedRoles.stream()
                .map(roleName -> roleRepository.findByName(roleName)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Роль не найдена: " + roleName
                        )))
                .collect(Collectors.toSet());
    }

    private boolean isEnabledAdmin(UserEntity user) {
        return user.isEnabled() && hasAdminRole(user);
    }

    private boolean hasAdminRole(UserEntity user) {
        return user.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .anyMatch("ADMIN"::equalsIgnoreCase);
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

    private String normalizeFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }

        return fullName.trim();
    }
}