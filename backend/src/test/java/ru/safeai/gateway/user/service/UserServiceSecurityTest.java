package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.dto.PermanentDeleteUserRequest;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceSecurityTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ADMIN_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID USER_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuditEventService auditEventService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock UserSessionRevocationService userSessionRevocationService;

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
    void adminCannotSeeUserFromAnotherOrganization() {
        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                userService.findDetailsById(USER_ID, adminPrincipal())
        )
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Пользователь не найден");
    }

    @Test
    void invalidRoleFilterIsRejected() {
        assertThatThrownBy(() ->
                userService.findAll(
                        adminPrincipal(),
                        "SUPER_ADMIN",
                        org.springframework.data.domain.PageRequest.of(0, 20)
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Недопустимый фильтр роли");
    }

    @Test
    void passwordResetIncrementsTokenVersionRevokesSessionsAndAudits() {
        UserEntity user = userEntity(Set.of(role("USER")), true);

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.encode("Strong_New_123!"))
                .thenReturn("new-hash");
        when(userRepository.save(user)).thenReturn(user);

        userService.resetPassword(
                USER_ID,
                new ResetUserPasswordRequest("Strong_New_123!"),
                adminPrincipal()
        );

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(1L);

        verify(userSessionRevocationService).revokeAllForUser(USER_ID);
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));
        verify(auditEventService).record(
                eq(ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_PASSWORD_RESET),
                anyMap()
        );
    }

    @Test
    void disablingUserRevokesSessionsAndIncrementsTokenVersion() {
        UserEntity user = userEntity(Set.of(role("USER")), true);

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var response = userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(false),
                adminPrincipal()
        );

        assertThat(response.enabled()).isFalse();
        assertThat(user.getTokenVersion()).isEqualTo(1L);

        verify(userSessionRevocationService).revokeAllForUser(USER_ID);
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));
    }

    @Test
    void adminCannotManageAnotherAdmin() {
        UserEntity targetAdmin = userEntity(Set.of(role("ADMIN")), true);

        when(userRepository.findByIdAndOrganizationId(USER_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(targetAdmin));

        assertThatThrownBy(() ->
                userService.updateRoles(
                        USER_ID,
                        new UpdateUserRolesRequest(Set.of("USER")),
                        adminPrincipal()
                )
        )
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN не может управлять другим ADMIN");

        verify(userRepository, never()).save(any());
    }

    @Test
    void permanentDeletionRejectsWrongConfirmationEmail() {
        UserEntity user = userEntity(Set.of(role("USER")), false);

        when(userRepository.findByIdWithRolesAndOrganization(USER_ID))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() ->
                userService.permanentlyDelete(
                        USER_ID,
                        new PermanentDeleteUserRequest("wrong@test.com"),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Email подтверждения");

        verify(userRepository, never()).delete(any());
    }

    @Test
    void permanentDeletionRejectsDependencies() {
        UserEntity user = userEntity(Set.of(role("USER")), false);

        when(userRepository.findByIdWithRolesAndOrganization(USER_ID))
                .thenReturn(Optional.of(user));
        when(userRepository.hasPermanentDeletionDependencies(USER_ID))
                .thenReturn(true);

        assertThatThrownBy(() ->
                userService.permanentlyDelete(
                        USER_ID,
                        new PermanentDeleteUserRequest(user.getEmail()),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Отключите пользователя");

        verify(userRepository, never()).delete(any());
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void permanentDeletionDeletesEmptyUserAndWritesAudit() {
        UserEntity user = userEntity(Set.of(role("USER")), false);

        when(userRepository.findByIdWithRolesAndOrganization(USER_ID))
                .thenReturn(Optional.of(user));
        when(userRepository.hasPermanentDeletionDependencies(USER_ID))
                .thenReturn(false);

        userService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest(user.getEmail()),
                superAdminPrincipal()
        );

        verify(userSessionRevocationService).revokeAllForUser(USER_ID);
        verify(userRepository).delete(user);
        verify(userRepository).flush();
        verify(eventPublisher).publishEvent(any(UserSecurityStateChangedEvent.class));
        verify(auditEventService).record(
                eq(SUPER_ADMIN_ID),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.USER_PERMANENTLY_DELETED),
                anyMap()
        );
    }

    private UserEntity userEntity(Set<RoleEntity> roles, boolean enabled) {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("Organization");
        organization.setEnabled(true);
        organization.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setOrganization(organization);
        user.setEmail("user@test.com");
        user.setPasswordHash("old-hash");
        user.setFullName("Test User");
        user.setEnabled(enabled);
        user.setCreatedAt(Instant.parse("2026-06-12T12:00:00Z"));
        user.setUpdatedAt(Instant.parse("2026-06-13T12:00:00Z"));
        user.setRoles(new HashSet<>(roles));
        user.setTokenVersion(0L);
        return user;
    }

    private RoleEntity role(String name) {
        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName(name);
        return role;
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
