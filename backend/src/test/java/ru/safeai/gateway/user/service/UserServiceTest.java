package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.exception.UserVersionConflictException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
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
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION_A_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ORGANIZATION_B_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ADMIN_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("22222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID =
            UUID.fromString("33333333-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("44444444-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW =
            Instant.parse("2026-07-25T12:00:00Z");
    private static final String VALID_PASSWORD = "Strong_User_123!";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditEventService auditEventService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserSessionRevocationService userSessionRevocationService;

    @Mock
    private EntityManager entityManager;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                roleRepository,
                passwordEncoder,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                new PlatformProperties(PLATFORM_ORGANIZATION_ID),
                new UserManagementProperties(Duration.ZERO),
                entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void adminCannotSeeUserFromAnotherOrganization() {
        when(userRepository.findByIdAndOrganizationId(
                OTHER_USER_ID,
                ORGANIZATION_A_ID
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                userService.findDetailsById(
                        OTHER_USER_ID,
                        adminPrincipal()
                ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Пользователь не найден");
    }

    @Test
    void adminCannotMutateUserFromAnotherOrganization() {
        when(userRepository.findByIdAndOrganizationId(
                OTHER_USER_ID,
                ORGANIZATION_A_ID
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                userService.updateUser(
                        OTHER_USER_ID,
                        new UpdateUserRequest(
                                "other@test.com",
                                "Other User",
                                0L
                        ),
                        adminPrincipal()
                ))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(userRepository, never())
                .findByIdForSecurityUpdate(any());
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void adminCannotPermanentlyDeleteUserFromAnotherOrganization() {
        assertThatThrownBy(() -> userService.permanentlyDelete(
                OTHER_USER_ID,
                new PermanentDeleteUserRequest("other@test.com", 0L),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Только SUPER_ADMIN");

        verifyNoInteractions(userRepository);
    }

    @Test
    void adminCannotCreateUserInAnotherOrganization() {
        CreateUserRequest request = new CreateUserRequest(
                ORGANIZATION_B_ID,
                "user-b@test.com",
                VALID_PASSWORD,
                "User B",
                Set.of("USER")
        );

        assertThatThrownBy(() ->
                userService.create(request, adminPrincipal()))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("другой организации");

        verifyNoInteractions(
                roleRepository,
                passwordEncoder,
                auditEventService
        );
    }

    @Test
    void createRejectsMalformedEmailAtServiceBoundary() {
        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        "definitely-not-an-email",
                        VALID_PASSWORD,
                        "Malformed Email",
                        Set.of("USER")
                ),
                adminPrincipal()
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Некорректный формат email");

        verifyNoInteractions(
                userRepository,
                roleRepository,
                passwordEncoder,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                entityManager
        );
    }

    @Test
    void createRejectsWeakPasswordBeforeRepositoryAccess() {
        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        "weak@test.com",
                        "weak",
                        "Weak",
                        Set.of("USER")
                ),
                adminPrincipal()
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Пароль");

        verifyNoInteractions(
                userRepository,
                roleRepository,
                passwordEncoder,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                entityManager
        );
    }

    @Test
    void createRejectsExistingEmailBeforeBcryptAndLock() {
        when(userRepository.existsByEmail("existing@test.com"))
                .thenReturn(true);

        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        " Existing@Test.com ",
                        VALID_PASSWORD,
                        "Existing",
                        Set.of("USER")
                ),
                adminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже существует");

        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(
                roleRepository,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                entityManager
        );
    }

    @Test
    void adminCreatesUserWithOnlyUserRole() {
        OrganizationEntity organization = organization(
                ORGANIZATION_A_ID,
                true
        );
        RoleEntity userRole = role("USER");

        stubOrganizationLock(organization);
        when(userRepository.existsByEmail("new-user@test.com"))
                .thenReturn(false);
        when(roleRepository.findByName("USER"))
                .thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode(VALID_PASSWORD))
                .thenReturn("encoded-password");
        stubSaveAndRefresh();

        UserResponse response = userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        " New-User@Test.com ",
                        VALID_PASSWORD,
                        " New   User ",
                        Set.of("USER")
                ),
                adminPrincipal()
        );

        assertThat(response.email()).isEqualTo("new-user@test.com");
        assertThat(response.fullName()).isEqualTo("New User");
        assertThat(response.roles()).containsExactly("USER");

        ArgumentCaptor<UserEntity> captor =
                ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRoles())
                .extracting(RoleEntity::getName)
                .containsExactly("USER");
    }

    @Test
    void adminCannotCreateAdmin() {
        OrganizationEntity organization = organization(
                ORGANIZATION_A_ID,
                true
        );
        stubOrganizationLock(organization);
        when(userRepository.existsByEmail("new-admin@test.com"))
                .thenReturn(false);

        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        "new-admin@test.com",
                        VALID_PASSWORD,
                        "New Admin",
                        Set.of("ADMIN")
                ),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("только роль USER");

        verify(roleRepository, never()).findByName(any());
    }

    @Test
    void superAdminCannotCreateSuperAdminThroughEndpoint() {
        OrganizationEntity organization = organization(
                ORGANIZATION_A_ID,
                true
        );
        stubOrganizationLock(organization);
        when(userRepository.existsByEmail("new-super@test.com"))
                .thenReturn(false);

        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        "new-super@test.com",
                        VALID_PASSWORD,
                        "New Super Admin",
                        Set.of("SUPER_ADMIN")
                ),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("SUPER_ADMIN нельзя назначать");
    }

    @Test
    void existingSuperAdminCannotBeModifiedThroughEndpoint() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "SUPER_ADMIN"
        );
        stubMutationAsSuperAdmin(target);

        assertThatThrownBy(() -> userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(false, 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("SUPER_ADMIN нельзя изменять");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void adminCannotManageAnotherAdmin() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "ADMIN"
        );
        stubMutationAsAdmin(target);

        assertThatThrownBy(() -> userService.updateRoles(
                USER_ID,
                new UpdateUserRolesRequest(Set.of("USER"), 0L),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("ADMIN не может управлять");
    }

    @Test
    void selfRoleMutationIsBlocked() {
        UserEntity currentAdmin = user(
                ADMIN_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "ADMIN"
        );
        stubMutationAsAdmin(currentAdmin);

        assertThatThrownBy(() -> userService.updateRoles(
                ADMIN_ID,
                new UpdateUserRolesRequest(Set.of("USER"), 0L),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("собственные роли");
    }

    @Test
    void lastActiveAdminCannotBeDisabled() {
        UserEntity targetAdmin = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "ADMIN"
        );
        stubMutationAsSuperAdmin(targetAdmin);
        when(userRepository.findEnabledAdminsForUpdate(
                ORGANIZATION_A_ID
        )).thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(false, 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("последнего активного администратора");
    }

    @Test
    void adminRoleCannotBeRemovedFromLastActiveAdmin() {
        UserEntity targetAdmin = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "ADMIN"
        );
        stubMutationAsSuperAdmin(targetAdmin);
        when(userRepository.findEnabledAdminsForUpdate(
                ORGANIZATION_A_ID
        )).thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> userService.updateRoles(
                USER_ID,
                new UpdateUserRolesRequest(Set.of("USER"), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("последнего активного администратора");
    }

    @Test
    void lastActiveAdminCannotBePermanentlyDeleted() {
        UserEntity targetAdmin = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "ADMIN"
        );
        stubMutationAsSuperAdmin(targetAdmin);
        when(userRepository.findEnabledAdminsForUpdate(
                ORGANIZATION_A_ID
        )).thenReturn(List.of(targetAdmin));

        assertThatThrownBy(() -> userService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest(targetAdmin.getEmail(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("последнего активного администратора");
    }

    @Test
    void staleExpectedVersionRejectsMutationBeforeSave() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "USER"
        );
        target.setVersion(3L);
        stubMutationAsAdmin(target);

        assertThatThrownBy(() -> userService.updateUser(
                USER_ID,
                new UpdateUserRequest(
                        "new@test.com",
                        "New Name",
                        2L
                ),
                adminPrincipal()
        ))
                .isInstanceOf(UserVersionConflictException.class);

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userSessionRevocationService);
    }

    @Test
    void unchangedUserUpdateHasNoPersistenceSecurityOrAuditSideEffects() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "USER"
        );
        stubMutationAsAdmin(target);

        UserResponse response = userService.updateUser(
                USER_ID,
                new UpdateUserRequest(
                        target.getEmail(),
                        target.getFullName(),
                        0L
                ),
                adminPrincipal()
        );

        assertThat(response.version()).isZero();
        assertThat(target.getTokenVersion()).isZero();
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(
                userSessionRevocationService,
                eventPublisher,
                auditEventService
        );
    }

    @Test
    void emailChangeIncrementsTokenVersionAndRevokesSessions() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "USER"
        );
        stubMutationAsAdmin(target);
        when(userRepository.existsByEmail("new-email@test.com"))
                .thenReturn(false);
        stubSaveAndRefresh();

        userService.updateUser(
                USER_ID,
                new UpdateUserRequest(
                        "new-email@test.com",
                        target.getFullName(),
                        0L
                ),
                adminPrincipal()
        );

        assertThat(target.getTokenVersion()).isEqualTo(1L);
        verify(userSessionRevocationService).revokeAllForUser(
                USER_ID,
                RefreshTokenRevocationReason.EMAIL_CHANGED
        );
        verify(eventPublisher).publishEvent(
                any(UserSecurityStateChangedEvent.class)
        );
    }

    @Test
    void roleChangeIncrementsTokenVersionAndRevokesSessions() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "USER"
        );
        stubMutationAsSuperAdmin(target);
        when(roleRepository.findByName("ADMIN"))
                .thenReturn(Optional.of(role("ADMIN")));
        stubSaveAndRefresh();

        userService.updateRoles(
                USER_ID,
                new UpdateUserRolesRequest(Set.of("ADMIN"), 0L),
                superAdminPrincipal()
        );

        assertThat(target.getTokenVersion()).isEqualTo(1L);
        verify(userSessionRevocationService).revokeAllForUser(
                USER_ID,
                RefreshTokenRevocationReason.ROLE_CHANGED
        );
    }

    @Test
    void passwordResetIncrementsTokenVersionAndRevokesSessions() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "USER"
        );
        stubMutationAsAdmin(target);
        when(passwordEncoder.encode(VALID_PASSWORD))
                .thenReturn("new-hash");
        stubSaveAndRefresh();

        userService.resetPassword(
                USER_ID,
                new ResetUserPasswordRequest(VALID_PASSWORD, 0L),
                adminPrincipal()
        );

        assertThat(target.getPasswordHash()).isEqualTo("new-hash");
        assertThat(target.getTokenVersion()).isEqualTo(1L);
        verify(userSessionRevocationService).revokeAllForUser(
                USER_ID,
                RefreshTokenRevocationReason.PASSWORD_RESET
        );
    }

    @Test
    void disablingUserIncrementsTokenVersionAndRevokesSessions() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "USER"
        );
        stubMutationAsAdmin(target);
        stubSaveAndRefresh();

        UserResponse response = userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(false, 0L),
                adminPrincipal()
        );

        assertThat(response.enabled()).isFalse();
        assertThat(target.getDisabledAt()).isEqualTo(NOW);
        assertThat(target.getTokenVersion()).isEqualTo(1L);
        verify(userSessionRevocationService).revokeAllForUser(
                USER_ID,
                RefreshTokenRevocationReason.USER_DISABLED
        );
    }

    @Test
    void unchangedEnabledStateHasNoSecurityOrAuditSideEffects() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "USER"
        );
        stubMutationAsAdmin(target);

        UserResponse response = userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(true, 0L),
                adminPrincipal()
        );

        assertThat(response.enabled()).isTrue();
        assertThat(target.getTokenVersion()).isZero();
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(
                userSessionRevocationService,
                eventPublisher,
                auditEventService
        );
    }

    @Test
    void enablingUserIncrementsTokenVersionWithoutRestoringSessions() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                false,
                "USER"
        );
        stubMutationAsAdmin(target);
        stubSaveAndRefresh();

        UserResponse response = userService.updateEnabled(
                USER_ID,
                new UpdateUserEnabledRequest(true, 0L),
                adminPrincipal()
        );

        assertThat(response.enabled()).isTrue();
        assertThat(target.getDisabledAt()).isNull();
        assertThat(target.getTokenVersion()).isEqualTo(1L);
        verify(userSessionRevocationService, never())
                .revokeAllForUser(any(), any());
    }

    @Test
    void selfDeletionIsBlocked() {
        UserEntity target = user(
                SUPER_ADMIN_ID,
                organization(ORGANIZATION_A_ID, true),
                false,
                "USER"
        );
        stubMutationAsSuperAdmin(target);

        assertThatThrownBy(() -> userService.permanentlyDelete(
                SUPER_ADMIN_ID,
                new PermanentDeleteUserRequest(target.getEmail(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("собственную учётную запись");
    }

    @Test
    void superAdminDeletionIsBlocked() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                false,
                "SUPER_ADMIN"
        );
        stubMutationAsSuperAdmin(target);

        assertThatThrownBy(() -> userService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest(target.getEmail(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("SUPER_ADMIN нельзя изменять");
    }

    @Test
    void platformOrganizationUserDeletionIsBlocked() {
        UserEntity target = user(
                USER_ID,
                organization(PLATFORM_ORGANIZATION_ID, true),
                false,
                "USER"
        );
        stubMutationAsSuperAdmin(target);

        assertThatThrownBy(() -> userService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest(target.getEmail(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("платформенной организации");
    }

    @Test
    void permanentDeletionRequiresMatchingConfirmationEmail() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                false,
                "USER"
        );
        stubMutationAsSuperAdmin(target);

        assertThatThrownBy(() -> userService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest("wrong@test.com", 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Email подтверждения");
    }

    @Test
    void permanentDeletionRequiresDisabledUser() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                true,
                "USER"
        );
        stubMutationAsSuperAdmin(target);

        assertThatThrownBy(() -> userService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest(target.getEmail(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("необходимо отключить");
    }

    @Test
    void permanentDeletionBeforeRetentionExpiresIsBlocked() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                false,
                "USER"
        );
        target.setDisabledAt(NOW.minus(Duration.ofDays(1)));
        stubMutationAsSuperAdmin(target);

        UserService retentionService = new UserService(
                userRepository,
                roleRepository,
                passwordEncoder,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                new PlatformProperties(PLATFORM_ORGANIZATION_ID),
                new UserManagementProperties(Duration.ofDays(7)),
                entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> retentionService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest(target.getEmail(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("Период хранения");

        verify(userRepository, never()).delete(any());
    }

    @Test
    void permanentDeletionRejectsActiveRefreshToken() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                false,
                "USER"
        );
        stubMutationAsSuperAdmin(target);
        when(userRepository.hasActiveRefreshTokens(USER_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> userService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest(target.getEmail(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("refresh-сессии");
    }

    @Test
    void permanentDeletionRejectsDependencies() {
        UserEntity target = user(
                USER_ID,
                organization(ORGANIZATION_A_ID, true),
                false,
                "USER"
        );
        stubMutationAsSuperAdmin(target);
        when(userRepository.hasPermanentDeletionDependencies(USER_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> userService.permanentlyDelete(
                USER_ID,
                new PermanentDeleteUserRequest(target.getEmail(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("связанные данные");
    }

    @Test
void permanentDeletionDeletesEmptyDisabledUser() {
    UserEntity target = user(
            USER_ID,
            organization(
                    ORGANIZATION_A_ID,
                    true
            ),
            false,
            "USER"
    );

    stubMutationAsSuperAdmin(target);

    when(userRepository.hasActiveRefreshTokens(USER_ID))
            .thenReturn(false);

    when(userRepository.hasPermanentDeletionDependencies(USER_ID))
            .thenReturn(false);

    SafeAiUserPrincipal currentUser =
            superAdminPrincipal();

    userService.permanentlyDelete(
            USER_ID,
            new PermanentDeleteUserRequest(
                    target.getEmail(),
                    0L
            ),
            currentUser
    );

    verify(userSessionRevocationService)
            .revokeAllForUser(
                    USER_ID,
                    RefreshTokenRevocationReason.ADMIN_REVOKED
            );

    verify(userRepository)
            .delete(target);

    verify(userRepository)
            .flush();

    verify(auditEventService).record(
            same(currentUser),
            eq(ORGANIZATION_A_ID),
            any(),
            anyMap()
    );
}

    @Test
    void cannotCreateUserInDisabledOrganization() {
        OrganizationEntity disabledOrganization = organization(
                ORGANIZATION_A_ID,
                false
        );

        when(userRepository.existsByEmail(
                "new-user@test.com"
        )).thenReturn(false);

        when(passwordEncoder.encode(
                VALID_PASSWORD
        )).thenReturn("encoded-password");

        stubOrganizationLock(disabledOrganization);

        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        "new-user@test.com",
                        VALID_PASSWORD,
                        "New User",
                        Set.of("USER")
                ),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("отключенной организации");

        /*
         * Предварительный unique-check и BCrypt выполняются до захвата
         * organization PESSIMISTIC_WRITE. Это намеренно: дорогое хеширование
         * не должно выполняться под DB lock. После lock enabled проверяется
         * повторно как security/correctness boundary.
         */
        verify(userRepository).existsByEmail(
                "new-user@test.com"
        );

        verify(passwordEncoder).encode(
                VALID_PASSWORD
        );

        verify(userRepository, never())
                .saveAndFlush(any());

        verifyNoInteractions(
                roleRepository,
                auditEventService
        );
    }

    private void stubMutationAsAdmin(UserEntity target) {
        when(userRepository.findByIdAndOrganizationId(
                target.getId(),
                ORGANIZATION_A_ID
        )).thenReturn(Optional.of(target));
        stubLockedTarget(target);
    }

    private void stubMutationAsSuperAdmin(UserEntity target) {
        when(userRepository.findByIdWithRolesAndOrganization(
                target.getId()
        )).thenReturn(Optional.of(target));
        stubLockedTarget(target);
    }

    private void stubLockedTarget(UserEntity target) {
        stubOrganizationLock(target.getOrganization());
        when(userRepository.findByIdForSecurityUpdate(target.getId()))
                .thenReturn(Optional.of(target));
    }

    private void stubOrganizationLock(OrganizationEntity organization) {
        when(entityManager.find(
                OrganizationEntity.class,
                organization.getId(),
                LockModeType.PESSIMISTIC_WRITE
        )).thenReturn(organization);
    }

    private void stubSaveAndRefresh() {
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenAnswer(invocation -> {
                    UserEntity user = invocation.getArgument(0);
                    if (user.getId() == null) {
                        user.setId(UUID.randomUUID());
                    }
                    return user;
                });
    }

    private OrganizationEntity organization(
            UUID id,
            boolean enabled
    ) {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(id);
        organization.setName("Organization " + id);
        organization.setEnabled(enabled);
        organization.setCreatedAt(NOW.minus(Duration.ofDays(30)));
        organization.setUpdatedAt(NOW.minus(Duration.ofDays(1)));
        return organization;
    }

    private UserEntity user(
            UUID id,
            OrganizationEntity organization,
            boolean enabled,
            String roleName
    ) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOrganization(organization);
        user.setEmail("user-" + id + "@test.com");
        user.setPasswordHash("old-hash");
        user.setFullName("Test User");
        user.setEnabled(enabled);
        user.setDisabledAt(
                enabled ? null : NOW.minus(Duration.ofDays(10))
        );
        user.setCreatedAt(NOW.minus(Duration.ofDays(30)));
        user.setUpdatedAt(NOW.minus(Duration.ofDays(1)));
        user.setTokenVersion(0L);
        user.setRoles(new HashSet<>(Set.of(role(roleName))));
        return user;
    }

    private RoleEntity role(String name) {
        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName(name);
        return role;
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                ADMIN_ID,
                ORGANIZATION_A_ID,
                0L,
                0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                0L,
                0L,
                Set.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
    }
}
