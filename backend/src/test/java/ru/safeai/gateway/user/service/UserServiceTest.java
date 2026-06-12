package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID ADMIN_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID USER_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditEventService auditEventService;

    @InjectMocks
    private UserService userService;

    @Test
    void createWhenEmailAlreadyExistsThrowsConflictException() {
        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_ID,
                "admin@test.com",
                "admin123",
                "Demo Admin",
                Set.of("ADMIN")
        );

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Пользователь с таким email уже существует");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenOrganizationNotFoundThrowsResourceNotFoundException() {
        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_ID,
                "admin@test.com",
                "admin123",
                "Demo Admin",
                Set.of("ADMIN")
        );

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(ORGANIZATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Организация не найдена");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenRoleNotFoundThrowsResourceNotFoundException() {
        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_ID,
                "admin@test.com",
                "admin123",
                "Demo Admin",
                Set.of("ADMIN")
        );

        OrganizationEntity organization = organizationEntity();

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(ORGANIZATION_ID)).thenReturn(Optional.of(organization));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Роль не найдена");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenRequestIsValidSavesUserWithEncodedPasswordAndRoles() {
        RoleEntity adminRole = roleEntity("ADMIN");

        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_ID,
                "admin@test.com",
                "admin123",
                "Demo Admin",
                Set.of("ADMIN")
        );

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(ORGANIZATION_ID)).thenReturn(Optional.of(organizationEntity()));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode("admin123")).thenReturn("encoded-password");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.create(request);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());

        UserEntity savedEntity = captor.getValue();

        assertThat(savedEntity.getId()).isNotNull();
        assertThat(savedEntity.getOrganization().getId()).isEqualTo(ORGANIZATION_ID);
        assertThat(savedEntity.getEmail()).isEqualTo("admin@test.com");
        assertThat(savedEntity.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(savedEntity.getFullName()).isEqualTo("Demo Admin");
        assertThat(savedEntity.isEnabled()).isTrue();
        assertThat(savedEntity.getRoles()).extracting(RoleEntity::getName).containsExactly("ADMIN");

        assertThat(response.email()).isEqualTo("admin@test.com");
        assertThat(response.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(response.roles()).containsExactly("ADMIN");
    }

    @Test
    void updateEnabledShouldDisableUser() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                true,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(USER_ID))
                .thenReturn(Optional.of(user));

        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(false),
                adminPrincipal()
        );

        assertThat(response.enabled()).isFalse();

        verify(userRepository).save(user);

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(AuditEventType.USER_ENABLED_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateEnabledShouldEnableUser() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                false,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(USER_ID))
                .thenReturn(Optional.of(user));

        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(true),
                adminPrincipal()
        );

        assertThat(response.enabled()).isTrue();

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(AuditEventType.USER_ENABLED_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateEnabledShouldThrowConflictWhenAdminDisablesSelf() {
        UserEntity admin = userEntity(
                ADMIN_ID,
                "admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(ADMIN_ID))
                .thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.updateEnabled(
                ADMIN_ID,
                new UpdateUserEnabledRequest(false),
                adminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Нельзя отключить самого себя");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
    }

    @Test
    void updateEnabledShouldThrowConflictWhenDisablingLastActiveAdmin() {
        UUID targetAdminId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

        UserEntity targetAdmin = userEntity(
                targetAdminId,
                "second-admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(targetAdminId))
                .thenReturn(Optional.of(targetAdmin));

        when(userRepository.countEnabledAdmins())
                .thenReturn(1L);

        assertThatThrownBy(() -> userService.updateEnabled(
                targetAdminId,
                new UpdateUserEnabledRequest(false),
                adminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("последнего активного администратора");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
    }

    @Test
    void updateRolesShouldChangeUserRoleToAdmin() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                true,
                Set.of(roleEntity("USER"))
        );

        RoleEntity adminRole = roleEntity("ADMIN");

        when(userRepository.findByIdWithRolesAndOrganization(USER_ID))
                .thenReturn(Optional.of(user));

        when(roleRepository.findByName("ADMIN"))
                .thenReturn(Optional.of(adminRole));

        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateRoles(
                USER_ID,
                new UpdateUserRolesRequest(Set.of("ADMIN")),
                adminPrincipal()
        );

        assertThat(response.roles()).containsExactly("ADMIN");

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(AuditEventType.USER_ROLES_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateRolesShouldThrowConflictWhenAdminRemovesOwnAdminRole() {
        UserEntity admin = userEntity(
                ADMIN_ID,
                "admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(ADMIN_ID))
                .thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.updateRoles(
                ADMIN_ID,
                new UpdateUserRolesRequest(Set.of("USER")),
                adminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Нельзя снять роль ADMIN с самого себя");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
    }

    @Test
    void updateRolesShouldThrowConflictWhenRemovingAdminRoleFromLastActiveAdmin() {
        UUID targetAdminId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

        UserEntity targetAdmin = userEntity(
                targetAdminId,
                "second-admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(targetAdminId))
                .thenReturn(Optional.of(targetAdmin));

        when(userRepository.countEnabledAdmins())
                .thenReturn(1L);

        assertThatThrownBy(() -> userService.updateRoles(
                targetAdminId,
                new UpdateUserRolesRequest(Set.of("USER")),
                adminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Нельзя снять роль ADMIN с последнего активного администратора");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
    }

    @Test
    void resetPasswordShouldUpdatePasswordHash() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                true,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(USER_ID))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.encode("NewPass123"))
                .thenReturn("new-encoded-password");

        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.resetPassword(
                USER_ID,
                new ResetUserPasswordRequest("NewPass123"),
                adminPrincipal()
        );

        assertThat(response.id()).isEqualTo(USER_ID);
        assertThat(user.getPasswordHash()).isEqualTo("new-encoded-password");

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(AuditEventType.USER_PASSWORD_RESET),
                anyMap()
        );
    }

    private OrganizationEntity organizationEntity() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Demo Company");
        organization.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        return organization;
    }

    private RoleEntity roleEntity(String name) {
        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName(name);
        return role;
    }

    private UserEntity userEntity(
            UUID id,
            String email,
            boolean enabled,
            Set<RoleEntity> roles
    ) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOrganization(organizationEntity());
        user.setEmail(email);
        user.setPasswordHash("encoded-password");
        user.setFullName("Test User");
        user.setEnabled(enabled);
        user.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        user.setRoles(roles);
        return user;
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return new SafeAiUserPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "encoded-password",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }
}