package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.event.UserSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceSecurityTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString("99999999-9999-9999-9999-999999999999");

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID ADMIN_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID OTHER_ADMIN_ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private static final UUID USER_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final UUID SAVED_USER_ID =
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private static final Instant SAVED_AT =
            Instant.parse("2026-06-12T12:00:00Z");

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

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserSessionRevocationService userSessionRevocationService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                roleRepository,
                organizationRepository,
                passwordEncoder,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                new PlatformProperties(PLATFORM_ORGANIZATION_ID)
        );
    }

    @Test
    void adminCannotCreateAdmin() {
        when(userRepository.existsByEmailIgnoreCase("new-admin@test.com"))
                .thenReturn(false);

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organizationEntity()));

        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_ID,
                "new-admin@test.com",
                "Strong_Admin_123!",
                "New Admin",
                Set.of("ADMIN")
        );

        assertThatThrownBy(() -> userService.create(request, adminPrincipal()))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN может назначать только роль USER");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void adminCanCreateUserInOwnOrganization() {
        when(userRepository.existsByEmailIgnoreCase("user@test.com"))
                .thenReturn(false);

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organizationEntity()));

        when(roleRepository.findByName("USER"))
                .thenReturn(Optional.of(roleEntity("USER")));

        when(passwordEncoder.encode("Strong_User_123!"))
                .thenReturn("encoded-password");

        stubUserSaveAndFlush();

        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_ID,
                "user@test.com",
                "Strong_User_123!",
                "User",
                Set.of("USER")
        );

        var response = userService.create(request, adminPrincipal());

        assertThat(response.email()).isEqualTo("user@test.com");
        assertThat(response.roles()).containsExactly("USER");

        verify(userRepository).saveAndFlush(any(UserEntity.class));
    }

    @Test
    void adminCannotCreateUserInAnotherOrganization() {
        CreateUserRequest request = new CreateUserRequest(
                OTHER_ORGANIZATION_ID,
                "user@test.com",
                "Strong_User_123!",
                "User",
                Set.of("USER")
        );

        assertThatThrownBy(() -> userService.create(request, adminPrincipal()))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Нельзя создавать пользователя в другой организации");

        verifyNoInteractions(organizationRepository);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void superAdminCanCreateAdminInNormalOrganization() {
        when(userRepository.existsByEmailIgnoreCase("admin@test.com"))
                .thenReturn(false);

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organizationEntity()));

        when(roleRepository.findByName("ADMIN"))
                .thenReturn(Optional.of(roleEntity("ADMIN")));

        when(passwordEncoder.encode("Strong_Admin_123!"))
                .thenReturn("encoded-password");

        stubUserSaveAndFlush();

        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_ID,
                "admin@test.com",
                "Strong_Admin_123!",
                "Admin",
                Set.of("ADMIN")
        );

        var response = userService.create(request, superAdminPrincipal());

        assertThat(response.roles()).containsExactly("ADMIN");

        verify(userRepository).saveAndFlush(any(UserEntity.class));
    }

    @Test
    void superAdminCannotCreateSuperAdminViaUserManagement() {
        when(userRepository.existsByEmailIgnoreCase("root@test.com"))
                .thenReturn(false);

        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organizationEntity()));

        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_ID,
                "root@test.com",
                "Strong_Root_123!",
                "Root",
                Set.of("SUPER_ADMIN")
        );

        assertThatThrownBy(() -> userService.create(request, superAdminPrincipal()))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("SUPER_ADMIN нельзя назначать");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void superAdminCannotCreateUserInPlatformOrganization() {
        CreateUserRequest request = new CreateUserRequest(
                PLATFORM_ORGANIZATION_ID,
                "user@test.com",
                "Strong_User_123!",
                "User",
                Set.of("USER")
        );

        assertThatThrownBy(() -> userService.create(request, superAdminPrincipal()))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("platform organization");

        verifyNoInteractions(organizationRepository);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void adminCannotUpdateRolesOfAdmin() {
        UserEntity targetAdmin = enabledUserEntity(
                OTHER_ADMIN_ID,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdAndOrganizationId(OTHER_ADMIN_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateRoles(
                OTHER_ADMIN_ID,
                new UpdateUserRolesRequest(Set.of("USER")),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN не может управлять другим ADMIN");

        verify(userRepository, never()).save(any());
    }

    @Test
    void adminCannotResetPasswordOfAdmin() {
        UserEntity targetAdmin = enabledUserEntity(
                OTHER_ADMIN_ID,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdAndOrganizationId(OTHER_ADMIN_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(targetAdmin));

        assertThatThrownBy(() -> userService.resetPassword(
                OTHER_ADMIN_ID,
                new ResetUserPasswordRequest("Strong_New_123!"),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN не может управлять другим ADMIN");

        verify(userRepository, never()).save(any());
    }

    @Test
    void adminCannotDisableAdmin() {
        UserEntity targetAdmin = enabledUserEntity(
                OTHER_ADMIN_ID,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdAndOrganizationId(OTHER_ADMIN_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateEnabled(
                OTHER_ADMIN_ID,
                new UpdateUserEnabledRequest(false),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN не может управлять другим ADMIN");

        verify(userRepository, never()).save(any());
    }

    @Test
    void adminCannotSeeUserFromAnotherOrganization() {
        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(USER_ID, adminPrincipal()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Пользователь не найден");
    }

    @Test
    void cannotDisableLastActiveAdmin() {
        UserEntity targetAdmin = enabledUserEntity(
                OTHER_ADMIN_ID,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(OTHER_ADMIN_ID))
                .thenReturn(Optional.of(targetAdmin));

        when(userRepository.findEnabledAdminsForUpdate(ORGANIZATION_ID))
                .thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateEnabled(
                OTHER_ADMIN_ID,
                new UpdateUserEnabledRequest(false),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("последнего активного администратора");

        verify(userRepository, never()).save(any());
    }

    @Test
    void cannotRemoveAdminRoleFromLastActiveAdmin() {
        UserEntity targetAdmin = enabledUserEntity(
                OTHER_ADMIN_ID,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(OTHER_ADMIN_ID))
                .thenReturn(Optional.of(targetAdmin));

        when(userRepository.findEnabledAdminsForUpdate(ORGANIZATION_ID))
                .thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateRoles(
                OTHER_ADMIN_ID,
                new UpdateUserRolesRequest(Set.of("USER")),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Нельзя снять роль ADMIN с последнего активного администратора");

        verify(userRepository, never()).save(any());
    }

    @Test
    void passwordResetIncrementsTokenVersionAndRevokesRefreshSessions() {
        UserEntity user = enabledUserEntity(
                USER_ID,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.encode("Strong_New_123!"))
                .thenReturn("new-hash");

        when(userRepository.save(user))
                .thenReturn(user);

        userService.resetPassword(
                USER_ID,
                new ResetUserPasswordRequest("Strong_New_123!"),
                adminPrincipal()
        );

        assertThat(user.getTokenVersion()).isEqualTo(1L);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");

        verify(userSessionRevocationService).revokeAllForUser(USER_ID);
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));
    }

    @Test
    void roleChangeIncrementsTokenVersionAndRevokesRefreshSessions() {
        UserEntity user = enabledUserEntity(
                USER_ID,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(USER_ID))
                .thenReturn(Optional.of(user));

        when(roleRepository.findByName("ADMIN"))
                .thenReturn(Optional.of(roleEntity("ADMIN")));

        when(userRepository.save(user))
                .thenReturn(user);

        userService.updateRoles(
                USER_ID,
                new UpdateUserRolesRequest(Set.of("ADMIN")),
                superAdminPrincipal()
        );

        assertThat(user.getTokenVersion()).isEqualTo(1L);
        assertThat(user.getRoles())
                .extracting(RoleEntity::getName)
                .containsExactly("ADMIN");

        verify(userSessionRevocationService).revokeAllForUser(USER_ID);
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));
    }

    private OrganizationEntity organizationEntity() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Organization");
        organization.setEnabled(true);
        organization.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        return organization;
    }

    private RoleEntity roleEntity(String name) {
        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName(name);
        return role;
    }

    private UserEntity enabledUserEntity(
            UUID id,
            Set<RoleEntity> roles
    ) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOrganization(organizationEntity());
        user.setEmail(id + "@test.com");
        user.setPasswordHash("old-hash");
        user.setFullName("Test User");
        user.setEnabled(true);
        user.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        user.setRoles(new HashSet<>(roles));
        user.setTokenVersion(0L);
        return user;
    }

    private void stubUserSaveAndFlush() {
        when(userRepository.saveAndFlush(
                any(UserEntity.class)
        )).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);

            if (entity.getId() == null) {
                entity.setId(SAVED_USER_ID);
            }

            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(SAVED_AT);
            }

            return entity;
        });
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return new SafeAiUserPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "hash",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return new SafeAiUserPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "super-admin@test.com",
                "hash",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
    }
}