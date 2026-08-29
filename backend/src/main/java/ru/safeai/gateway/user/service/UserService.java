package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.PermanentDeleteUserRequest;
import ru.safeai.gateway.user.dto.ResetUserPasswordRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.dto.UserDetailsResponse;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.dto.UserStatisticsResponse;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.util.UUID;

@Service
@EnableConfigurationProperties(UserManagementProperties.class)
public class UserService {

    private final UserCommandOperations commands;
    private final UserQueryOperations queries;

    /**
     * The constructor intentionally preserves the original dependency contract.
     * Existing Spring wiring and direct unit tests can therefore keep creating
     * UserService exactly as before while responsibilities are delegated to
     * smaller internal collaborators.
     */
    public UserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            AuditEventService auditEventService,
            ApplicationEventPublisher eventPublisher,
            UserSessionRevocationService userSessionRevocationService,
            PlatformProperties platformProperties,
            UserManagementProperties userManagementProperties,
            EntityManager entityManager,
            Clock clock
    ) {
        UserAccessPolicy accessPolicy =
                new UserAccessPolicy(platformProperties);
        UserResponseMapper responseMapper =
                new UserResponseMapper();
        UserEntityAccess entityAccess =
                new UserEntityAccess(
                        userRepository,
                        entityManager,
                        accessPolicy
                );
        UserRoleAssignmentResolver roleResolver =
                new UserRoleAssignmentResolver(
                        roleRepository,
                        accessPolicy
                );

        this.commands = new UserCommandOperations(
                userRepository,
                passwordEncoder,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                userManagementProperties,
                entityManager,
                clock,
                accessPolicy,
                entityAccess,
                roleResolver,
                responseMapper
        );

        this.queries = new UserQueryOperations(
                userRepository,
                accessPolicy,
                entityAccess,
                responseMapper
        );
    }

    @Transactional
    public UserResponse create(
            CreateUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        return commands.create(request, currentUser);
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public Page<UserResponse> findAll(
            SafeAiUserPrincipal currentUser,
            String role,
            Pageable pageable
    ) {
        return queries.findAll(currentUser, role, pageable);
    }

    @Transactional(readOnly = true)
    public UserDetailsResponse findDetailsById(
            UUID id,
            SafeAiUserPrincipal currentUser
    ) {
        return queries.findDetailsById(id, currentUser);
    }

    @Transactional(readOnly = true)
    public UserStatisticsResponse statistics(
            SafeAiUserPrincipal currentUser
    ) {
        return queries.statistics(currentUser);
    }

    @Transactional
    public UserResponse updateUser(
            UUID id,
            UpdateUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        return commands.updateUser(id, request, currentUser);
    }

    @Transactional
    public UserResponse updateEnabled(
            UUID id,
            UpdateUserEnabledRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        return commands.updateEnabled(id, request, currentUser);
    }

    @Transactional
    public UserResponse updateRoles(
            UUID id,
            UpdateUserRolesRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        return commands.updateRoles(id, request, currentUser);
    }

    @Transactional
    public void resetPassword(
            UUID id,
            ResetUserPasswordRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        commands.resetPassword(id, request, currentUser);
    }

    @Transactional
    public void permanentlyDelete(
            UUID id,
            PermanentDeleteUserRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        commands.permanentlyDelete(id, request, currentUser);
    }
}
