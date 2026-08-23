package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.dto.UserDetailsResponse;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.dto.UserStatisticsResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceReadTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID USER_1_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID USER_2_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-23T12:00:00Z"
            );

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
        userService =
                new UserService(
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
    void adminFindAllIsTenantScopedAndPreservesIdPageOrder() {
        Pageable requested =
                PageRequest.of(
                        0,
                        20,
                        Sort.by(
                                Sort.Order.asc(
                                        "email"
                                )
                        )
                );

        when(
                userRepository
                        .findAllIdsByOrganizationId(
                                eq(ORGANIZATION_ID),
                                any(Pageable.class)
                        )
        ).thenAnswer(invocation ->
                new PageImpl<>(
                        List.of(
                                USER_2_ID,
                                USER_1_ID
                        ),
                        invocation.getArgument(1),
                        2L
                )
        );

        when(
                userRepository
                        .findAllByIdsWithRolesAndOrganization(
                                List.of(
                                        USER_2_ID,
                                        USER_1_ID
                                )
                        )
        ).thenReturn(
                List.of(
                        user(
                                USER_1_ID,
                                "a@test.com"
                        ),
                        user(
                                USER_2_ID,
                                "b@test.com"
                        )
                )
        );

        var result =
                userService.findAll(
                        adminPrincipal(),
                        null,
                        requested
                );

        assertThat(
                result.getContent()
        )
                .extracting(
                        UserResponse::id
                )
                .containsExactly(
                        USER_2_ID,
                        USER_1_ID
                );

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(
                userRepository
        ).findAllIdsByOrganizationId(
                eq(ORGANIZATION_ID),
                pageableCaptor.capture()
        );

        assertThat(
                pageableCaptor
                        .getValue()
                        .getSort()
                        .getOrderFor(
                                "email"
                        )
        ).isNotNull();

        assertThat(
                pageableCaptor
                        .getValue()
                        .getSort()
                        .getOrderFor(
                                "id"
                        )
        ).isNotNull();

        verify(
                userRepository,
                never()
        ).findAllIds(
                any()
        );
    }

    @Test
    void superAdminRoleFilterIsCanonicalized() {
        Pageable requested =
                PageRequest.of(
                        0,
                        20
                );

        when(
                userRepository
                        .findAllIdsByRole(
                                eq("ADMIN"),
                                any(Pageable.class)
                        )
        ).thenAnswer(invocation ->
                new PageImpl<>(
                        List.of(),
                        invocation.getArgument(1),
                        0L
                )
        );

        userService.findAll(
                superAdminPrincipal(),
                " role_admin ",
                requested
        );

        verify(
                userRepository
        ).findAllIdsByRole(
                eq("ADMIN"),
                any(Pageable.class)
        );
    }

    @Test
    void findAllRejectsUnsupportedRoleFilterBeforeRepositoryAccess() {
        assertThatThrownBy(() ->
                userService.findAll(
                        adminPrincipal(),
                        "SUPER_ADMIN",
                        PageRequest.of(
                                0,
                                20
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "Недопустимый фильтр роли"
                );

        verifyNoInteractions(
                userRepository
        );
    }

    @Test
    void findAllRejectsUnsupportedSortBeforeRepositoryAccess() {
        assertThatThrownBy(() ->
                userService.findAll(
                        adminPrincipal(),
                        null,
                        PageRequest.of(
                                0,
                                20,
                                Sort.by(
                                        "tokenVersion"
                                )
                        )
                )
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "tokenVersion"
                );

        verifyNoInteractions(
                userRepository
        );
    }

    @Test
    void findDetailsReturnsOrganizationName() {
        UserEntity user =
                user(
                        USER_1_ID,
                        "user@test.com"
                );

        when(
                userRepository
                        .findByIdAndOrganizationId(
                                USER_1_ID,
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                Optional.of(
                        user
                )
        );

        UserDetailsResponse response =
                userService.findDetailsById(
                        USER_1_ID,
                        adminPrincipal()
                );

        assertThat(
                response.id()
        ).isEqualTo(
                USER_1_ID
        );

        assertThat(
                response.organizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                response.organizationName()
        ).isEqualTo(
                "Tenant A"
        );

        assertThat(
                response.roles()
        ).containsExactly(
                "USER"
        );
    }

    @Test
    void adminStatisticsAreTenantScoped() {
        when(
                userRepository
                        .countByOrganization_Id(
                                ORGANIZATION_ID
                        )
        ).thenReturn(
                10L
        );

        when(
                userRepository
                        .countByOrganizationIdAndRole(
                                ORGANIZATION_ID,
                                "ADMIN"
                        )
        ).thenReturn(
                2L
        );

        when(
                userRepository
                        .countByOrganizationIdAndRole(
                                ORGANIZATION_ID,
                                "USER"
                        )
        ).thenReturn(
                8L
        );

        when(
                userRepository
                        .countByOrganization_IdAndEnabled(
                                ORGANIZATION_ID,
                                true
                        )
        ).thenReturn(
                9L
        );

        when(
                userRepository
                        .countByOrganization_IdAndEnabled(
                                ORGANIZATION_ID,
                                false
                        )
        ).thenReturn(
                1L
        );

        UserStatisticsResponse result =
                userService.statistics(
                        adminPrincipal()
                );

        assertThat(
                result.total()
        ).isEqualTo(
                10L
        );

        assertThat(
                result.administrators()
        ).isEqualTo(
                2L
        );

        assertThat(
                result.users()
        ).isEqualTo(
                8L
        );

        assertThat(
                result.enabled()
        ).isEqualTo(
                9L
        );

        assertThat(
                result.disabled()
        ).isEqualTo(
                1L
        );
    }

    @Test
    void superAdminStatisticsAreGlobal() {
        when(
                userRepository.count()
        ).thenReturn(
                100L
        );

        when(
                userRepository.countByRole(
                        "ADMIN"
                )
        ).thenReturn(
                12L
        );

        when(
                userRepository.countByRole(
                        "USER"
                )
        ).thenReturn(
                88L
        );

        when(
                userRepository.countByEnabled(
                        true
                )
        ).thenReturn(
                90L
        );

        when(
                userRepository.countByEnabled(
                        false
                )
        ).thenReturn(
                10L
        );

        UserStatisticsResponse result =
                userService.statistics(
                        superAdminPrincipal()
                );

        assertThat(
                result.total()
        ).isEqualTo(
                100L
        );

        assertThat(
                result.administrators()
        ).isEqualTo(
                12L
        );

        assertThat(
                result.users()
        ).isEqualTo(
                88L
        );

        assertThat(
                result.enabled()
        ).isEqualTo(
                90L
        );

        assertThat(
                result.disabled()
        ).isEqualTo(
                10L
        );
    }

    private UserEntity user(
            UUID id,
            String email
    ) {
        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setId(
                ORGANIZATION_ID
        );

        organization.setName(
                "Tenant A"
        );

        organization.setEnabled(
                true
        );

        RoleEntity role =
                new RoleEntity();

        role.setId(
                UUID.randomUUID()
        );

        role.setName(
                "USER"
        );

        UserEntity user =
                new UserEntity();

        user.setId(
                id
        );

        user.setOrganization(
                organization
        );

        user.setEmail(
                email
        );

        user.setFullName(
                "User " + id
        );

        user.setEnabled(
                true
        );

        user.setRoles(
                new HashSet<>(
                        Set.of(
                                role
                        )
                )
        );

        user.setCreatedAt(
                NOW.minusSeconds(
                        60
                )
        );

        user.setUpdatedAt(
                NOW
        );

        user.setVersion(
                0L
        );

        return user;
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        ADMIN_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        SUPER_ADMIN_ID,
                        PLATFORM_ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }
}