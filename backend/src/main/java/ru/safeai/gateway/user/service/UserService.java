package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
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

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = request.email().trim().toLowerCase();

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

        Set<RoleEntity> roles = requestedRoles.stream()
                .map(roleName -> roleName.trim().toUpperCase())
                .map(roleName -> roleRepository.findByName(roleName)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Роль не найдена: " + roleName
                        )))
                .collect(Collectors.toSet());

        UserEntity entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organization);
        entity.setEmail(email);
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setFullName(request.fullName());
        entity.setEnabled(true);
        entity.setCreatedAt(Instant.now());
        entity.setRoles(roles);

        UserEntity saved = userRepository.save(entity);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userRepository.findAllWithRoles()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse findById(UUID id) {
        UserEntity user = userRepository.findByIdWithRolesAndOrganization(id)
                .orElseThrow(() -> new ResourceNotFoundException("Пользователь не найден: " + id));

        return toResponse(user);
    }

    private UserResponse toResponse(UserEntity entity) {
        Set<String> roleNames = entity.getRoles()
                .stream()
                .map(RoleEntity::getName)
                .collect(Collectors.toSet());

        return new UserResponse(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getEmail(),
                entity.getFullName(),
                entity.isEnabled(),
                roleNames,
                entity.getCreatedAt()
        );
    }
}