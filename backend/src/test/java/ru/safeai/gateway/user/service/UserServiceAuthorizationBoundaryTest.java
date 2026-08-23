package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserServiceAuthorizationBoundaryTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID ACTOR_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID TARGET_USER_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final String VALID_PASSWORD =
            "Strong_User_123!";

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
    private UserSessionRevocationService
            userSessionRevocationService;

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
                new PlatformProperties(
                        PLATFORM_ORGANIZATION_ID
                ),
                new UserManagementProperties(
                        Duration.ZERO
                ),
                entityManager,
                Clock.systemUTC()
        );
    }

    @Test
    void ordinaryUserCannotCallAnyUserManagementEntryPoint() {
        SafeAiUserPrincipal ordinaryUser =
                ordinaryUserPrincipal();

        assertForbidden(() ->
                userService.create(
                        new CreateUserRequest(
                                ORGANIZATION_ID,
                                "new-user@test.example",
                                VALID_PASSWORD,
                                "New User",
                                Set.of("USER")
                        ),
                        ordinaryUser
                )
        );

        assertForbidden(() ->
                userService.findAll(
                        ordinaryUser,
                        null,
                        PageRequest.of(0, 20)
                )
        );

        assertForbidden(() ->
                userService.statistics(
                        ordinaryUser
                )
        );

        assertForbidden(() ->
                userService.findDetailsById(
                        TARGET_USER_ID,
                        ordinaryUser
                )
        );

        assertForbidden(() ->
                userService.updateUser(
                        TARGET_USER_ID,
                        new UpdateUserRequest(
                                "target@test.example",
                                "Target User",
                                0L
                        ),
                        ordinaryUser
                )
        );

        assertForbidden(() ->
                userService.updateEnabled(
                        TARGET_USER_ID,
                        new UpdateUserEnabledRequest(
                                false,
                                0L
                        ),
                        ordinaryUser
                )
        );

        assertForbidden(() ->
                userService.updateRoles(
                        TARGET_USER_ID,
                        new UpdateUserRolesRequest(
                                Set.of("USER"),
                                0L
                        ),
                        ordinaryUser
                )
        );

        assertForbidden(() ->
                userService.resetPassword(
                        TARGET_USER_ID,
                        new ResetUserPasswordRequest(
                                VALID_PASSWORD,
                                0L
                        ),
                        ordinaryUser
                )
        );

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
    void findAllUsesRepeatableReadForTwoQueryPageSnapshot()
            throws Exception {
        Method method = UserService.class.getMethod(
                "findAll",
                SafeAiUserPrincipal.class,
                String.class,
                Pageable.class
        );

        Transactional transactional =
                method.getAnnotation(
                        Transactional.class
                );

        assertThat(transactional)
                .isNotNull();

        assertThat(transactional.readOnly())
                .isTrue();

        assertThat(transactional.isolation())
                .isEqualTo(
                        Isolation.REPEATABLE_READ
                );
    }

    private void assertForbidden(
            Runnable invocation
    ) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining(
                        "Только ADMIN или SUPER_ADMIN"
                );
    }

    private SafeAiUserPrincipal ordinaryUserPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        ACTOR_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_USER"
                                )
                        )
                );
    }
}
