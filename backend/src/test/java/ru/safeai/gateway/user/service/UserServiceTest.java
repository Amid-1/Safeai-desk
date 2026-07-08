package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.ConflictException;
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
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.event.UserSecurityStateChangedEvent;
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

    private static final UUID TARGET_ADMIN_ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String VALID_PASSWORD = "Admin123!456";
    private static final String NEW_VALID_PASSWORD = "NewPass123!45";

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
    void createWhenEmailAlreadyExistsThrowsConflictException() {
        CreateUserRequest request = createUserRequest(Set.of("USER"));

        when(userRepository.existsByEmailIgnoreCase("admin@test.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(request, adminPrincipal()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Пользователь с таким email уже существует");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenOrganizationNotFoundThrowsResourceNotFoundException() {
        CreateUserRequest request = createUserRequest(Set.of("USER"));

        when(userRepository.existsByEmailIgnoreCase("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(ORGANIZATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(request, adminPrincipal()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Организация не найдена");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenRoleNotFoundThrowsResourceNotFoundException() {
        CreateUserRequest request = createUserRequest(Set.of("USER"));

        when(userRepository.existsByEmailIgnoreCase("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organizationEntity()));
        when(roleRepository.findByName("USER")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(request, adminPrincipal()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Роль не найдена");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenAdminAssignsAdminRoleThrowsForbiddenOperationException() {
        CreateUserRequest request = createUserRequest(Set.of("ADMIN"));

        when(userRepository.existsByEmailIgnoreCase("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organizationEntity()));

        assertThatThrownBy(() -> userService.create(request, adminPrincipal()))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN может назначать только роль USER");

        verify(roleRepository, never()).findByName(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenSuperAdminCreatesAdminSavesUserWithEncodedPasswordAndRoles() {
        RoleEntity adminRole = roleEntity("ADMIN");

        CreateUserRequest request = createUserRequest(Set.of("ADMIN"));

        when(userRepository.existsByEmailIgnoreCase("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(ORGANIZATION_ID))
                .thenReturn(Optional.of(organizationEntity()));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn("encoded-password");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);

            if (user.getId() == null) {
                user.setId(UUID.randomUUID());
            }

            if (user.getCreatedAt() == null) {
                user.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
            }

            return user;
        });

        UserResponse response = userService.create(request, superAdminPrincipal());

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());

        UserEntity savedEntity = captor.getValue();

        assertThat(savedEntity.getId()).isNotNull();
        assertThat(savedEntity.getOrganization().getId()).isEqualTo(ORGANIZATION_ID);
        assertThat(savedEntity.getEmail()).isEqualTo("admin@test.com");
        assertThat(savedEntity.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(savedEntity.getFullName()).isEqualTo("Demo Admin");
        assertThat(savedEntity.isEnabled()).isTrue();
        assertThat(savedEntity.getRoles())
                .extracting(RoleEntity::getName)
                .containsExactly("ADMIN");

        assertThat(response.email()).isEqualTo("admin@test.com");
        assertThat(response.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(response.roles()).containsExactly("ADMIN");

        verify(auditEventService).record(
                eq(SUPER_ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_CREATED),
                anyMap()
        );

        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void updateEnabledShouldDisableUserAndRevokeRefreshSessions() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                true,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(user));

        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(false),
                adminPrincipal()
        );

        assertThat(response.enabled()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(1L);

        verify(userRepository).save(user);
        verify(userSessionRevocationService).revokeAllForUser(USER_ID);
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_ENABLED_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateEnabledShouldEnableUserWithoutRevokingRefreshSessions() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                false,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(user));

        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(true),
                adminPrincipal()
        );

        assertThat(response.enabled()).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(1L);

        verify(userSessionRevocationService, never()).revokeAllForUser(any());
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_ENABLED_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateEnabledShouldThrowForbiddenOperationExceptionWhenAdminDisablesSelf() {
        UserEntity admin = userEntity(
                ADMIN_ID,
                "admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdAndOrganizationId(ADMIN_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.updateEnabled(
                ADMIN_ID,
                new UpdateUserEnabledRequest(false),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Нельзя отключить самого себя");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void updateEnabledShouldThrowForbiddenOperationExceptionWhenAdminManagesAnotherAdmin() {
        UserEntity targetAdmin = userEntity(
                TARGET_ADMIN_ID,
                "second-admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdAndOrganizationId(TARGET_ADMIN_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateEnabled(
                TARGET_ADMIN_ID,
                new UpdateUserEnabledRequest(false),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN не может управлять другим ADMIN");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void updateEnabledShouldThrowForbiddenOperationExceptionWhenSuperAdminDisablesLastActiveAdmin() {
        UserEntity targetAdmin = userEntity(
                TARGET_ADMIN_ID,
                "second-admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(TARGET_ADMIN_ID))
                .thenReturn(Optional.of(targetAdmin));

        when(userRepository.findEnabledAdminsForUpdate(ORGANIZATION_ID))
                .thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateEnabled(
                TARGET_ADMIN_ID,
                new UpdateUserEnabledRequest(false),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("последнего активного администратора");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void updateRolesShouldChangeUserRoleToAdminAndRevokeRefreshSessionsWhenCurrentUserIsSuperAdmin() {
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
                superAdminPrincipal()
        );

        assertThat(response.roles()).containsExactly("ADMIN");
        assertThat(user.getTokenVersion()).isEqualTo(1L);

        verify(userSessionRevocationService).revokeAllForUser(USER_ID);
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(SUPER_ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_ROLES_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateRolesShouldThrowForbiddenOperationExceptionWhenAdminAssignsAdminRole() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                true,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateRoles(
                USER_ID,
                new UpdateUserRolesRequest(Set.of("ADMIN")),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN может назначать только роль USER");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void updateRolesShouldNotChangeTokenVersionWhenRolesAreSame() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                true,
                Set.of(roleEntity("USER"))
        );

        RoleEntity userRole = roleEntity("USER");

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(user));

        when(roleRepository.findByName("USER"))
                .thenReturn(Optional.of(userRole));

        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateRoles(
                USER_ID,
                new UpdateUserRolesRequest(Set.of("USER")),
                adminPrincipal()
        );

        assertThat(response.roles()).containsExactly("USER");
        assertThat(user.getTokenVersion()).isZero();

        verify(userSessionRevocationService, never()).revokeAllForUser(any());
        verify(eventPublisher, never()).publishEvent(any(UserSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_ROLES_CHANGED),
                anyMap()
        );
    }

    @Test
    void updateRolesShouldThrowForbiddenOperationExceptionWhenAdminRemovesOwnAdminRole() {
        UserEntity admin = userEntity(
                ADMIN_ID,
                "admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdAndOrganizationId(ADMIN_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.updateRoles(
                ADMIN_ID,
                new UpdateUserRolesRequest(Set.of("USER")),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Нельзя менять собственные роли");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void updateRolesShouldThrowForbiddenOperationExceptionWhenAdminManagesAnotherAdmin() {
        UserEntity targetAdmin = userEntity(
                TARGET_ADMIN_ID,
                "second-admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdAndOrganizationId(TARGET_ADMIN_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateRoles(
                TARGET_ADMIN_ID,
                new UpdateUserRolesRequest(Set.of("USER")),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN не может управлять другим ADMIN");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void updateRolesShouldThrowForbiddenOperationExceptionWhenSuperAdminRemovesAdminRoleFromLastActiveAdmin() {
        UserEntity targetAdmin = userEntity(
                TARGET_ADMIN_ID,
                "second-admin@test.com",
                true,
                Set.of(roleEntity("ADMIN"))
        );

        when(userRepository.findByIdWithRolesAndOrganization(TARGET_ADMIN_ID))
                .thenReturn(Optional.of(targetAdmin));

        when(userRepository.findEnabledAdminsForUpdate(ORGANIZATION_ID))
                .thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateRoles(
                TARGET_ADMIN_ID,
                new UpdateUserRolesRequest(Set.of("USER")),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Нельзя снять роль ADMIN с последнего активного администратора");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditEventService);
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void resetPasswordShouldUpdatePasswordHashAndRevokeRefreshSessions() {
        UserEntity user = userEntity(
                USER_ID,
                "user@test.com",
                true,
                Set.of(roleEntity("USER"))
        );

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.encode(NEW_VALID_PASSWORD))
                .thenReturn("new-encoded-password");

        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userService.resetPassword(
                USER_ID,
                new ResetUserPasswordRequest(NEW_VALID_PASSWORD),
                adminPrincipal()
        );

        assertThat(user.getPasswordHash()).isEqualTo("new-encoded-password");
        assertThat(user.getTokenVersion()).isEqualTo(1L);

        verify(userRepository).save(user);
        verify(userSessionRevocationService).revokeAllForUser(USER_ID);
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));

        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_PASSWORD_RESET),
                anyMap()
        );
    }

    private CreateUserRequest createUserRequest(Set<String> roles) {
        return new CreateUserRequest(
                ORGANIZATION_ID,
                "admin@test.com",
                VALID_PASSWORD,
                "Demo Admin",
                roles
        );
    }

    private OrganizationEntity organizationEntity() {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Demo Company");
        organization.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        organization.setEnabled(true);
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
        user.setTokenVersion(0L);
        return user;
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return new SafeAiUserPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "encoded-password",
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
                "encoded-password",
                true,
                0L,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
    }
}