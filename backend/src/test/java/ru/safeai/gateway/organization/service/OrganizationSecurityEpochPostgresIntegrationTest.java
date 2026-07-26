package ru.safeai.gateway.organization.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.auth.service.RefreshTokenService;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.UpdateOrganizationEnabledRequest;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.service.UserSecurityStatus;
import ru.safeai.gateway.user.service.UserService;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@SpringBootTest
@ActiveProfiles("test")
class OrganizationSecurityEpochPostgresIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-1111-1111-1111-111111111111"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-2222-2222-2222-222222222222"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "cccccccc-3333-3333-3333-333333333333"
            );

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserStatusCacheService userStatusCacheService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void insertSecuritySubjects() {
        insertOrganization(
                ORGANIZATION_ID,
                "Security Tenant",
                true
        );

        insertUser(
                USER_ID,
                ORGANIZATION_ID,
                "user@test.com",
                true,
                "USER",
                Instant.now().minusSeconds(60)
        );

        insertUser(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                true,
                "SUPER_ADMIN",
                Instant.now().minusSeconds(60)
        );
    }

    @Test
    void disableRevokesRefreshSessionsAndIncrementsOrganizationEpoch() {
        RefreshTokenService.CreatedRefreshToken token =
                createLoginToken();

        organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(false),
                superAdminPrincipal()
        );

        assertThat(organizationEnabled()).isFalse();
        assertThat(organizationAuthVersion()).isEqualTo(1L);
        assertThat(activeRefreshTokenCount()).isZero();

        String reason = jdbcTemplate.queryForObject("""
                select revocation_reason
                from public.refresh_tokens
                where id = ?
                """,
                String.class,
                token.id()
        );

        assertThat(reason)
                .isEqualTo("ORGANIZATION_DISABLED");
    }

    @Test
    void disableInvalidatesAccessSecurityStateImmediatelyWithCacheDisabled() {
        UserSecurityStatus before =
                userStatusCacheService.getStatus(USER_ID)
                        .orElseThrow();

        assertThat(before.organizationEnabled()).isTrue();
        assertThat(before.organizationAuthVersion()).isZero();

        organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(false),
                superAdminPrincipal()
        );

        UserSecurityStatus after =
                userStatusCacheService.getStatus(USER_ID)
                        .orElseThrow();

        assertThat(after.organizationEnabled()).isFalse();
        assertThat(after.organizationAuthVersion())
                .isEqualTo(1L);

        assertThat(after.organizationAuthVersion())
                .isNotEqualTo(
                        before.organizationAuthVersion()
                );
    }

    @Test
    void reEnableDoesNotRestoreOldRefreshToken() {
        RefreshTokenService.CreatedRefreshToken token =
                createLoginToken();

        disableOrganization();
        enableOrganization();

        assertThat(organizationEnabled()).isTrue();
        assertThat(organizationAuthVersion()).isEqualTo(1L);
        assertThat(activeRefreshTokenCount()).isZero();

        assertThatThrownBy(() ->
                refreshTokenService.rotate(
                        token.rawToken(),
                        request()
                )
        ).isInstanceOf(
                InvalidRefreshTokenException.class
        );
    }

    @Test
    void concurrentRefreshAndDisableLeaveNoUsableTokenAfterReEnable()
            throws Exception {
        RefreshTokenService.CreatedRefreshToken original =
                createLoginToken();

        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<?> refreshFuture = executor.submit(() -> {
                await(start);

                try {
                    refreshTokenService.rotate(
                            original.rawToken(),
                            request()
                    );
                } catch (InvalidRefreshTokenException ignored) {
                    // Disable may win the race. Both outcomes are safe.
                }
            });

            Future<?> disableFuture = executor.submit(() -> {
                await(start);
                disableOrganization();
            });

            start.countDown();

            refreshFuture.get(20, TimeUnit.SECONDS);
            disableFuture.get(20, TimeUnit.SECONDS);

            enableOrganization();

            assertThat(organizationAuthVersion())
                    .isEqualTo(1L);

            assertThat(usableActiveRefreshTokenCount())
                    .isZero();
        } finally {
            start.countDown();
        }
    }

    @Test
    void concurrentLoginAndDisableMayInsertStaleTokenButItIsNeverUsable()
            throws Exception {
        CountDownLatch userLoaded = new CountDownLatch(1);
        CountDownLatch continueLogin = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<?> loginFuture = executor.submit(() -> {
                TransactionTemplate transaction =
                        new TransactionTemplate(
                                transactionManager
                        );

                transaction.executeWithoutResult(status -> {
                    UserEntity user = userRepository
                            .findByIdWithRolesAndOrganization(
                                    USER_ID
                            )
                            .orElseThrow();

                    userLoaded.countDown();
                    await(continueLogin);

                    refreshTokenService.createForLogin(
                            user,
                            request(),
                            Instant.now()
                    );
                });
            });

            assertThat(userLoaded.await(
                    10,
                    TimeUnit.SECONDS
            )).isTrue();

            disableOrganization();
            continueLogin.countDown();

            loginFuture.get(20, TimeUnit.SECONDS);

            enableOrganization();

            assertThat(organizationAuthVersion())
                    .isEqualTo(1L);

            assertThat(usableActiveRefreshTokenCount())
                    .isZero();
        } finally {
            userLoaded.countDown();
            continueLogin.countDown();
        }
    }

    @Test
    void concurrentRoleChangeAndDisableKeepIndependentEpochs()
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<?> roleChangeFuture = executor.submit(() -> {
                await(start);

                userService.updateRoles(
                        USER_ID,
                        new UpdateUserRolesRequest(
                                Set.of("ADMIN")
                        ),
                        superAdminPrincipal()
                );
            });

            Future<?> disableFuture = executor.submit(() -> {
                await(start);
                disableOrganization();
            });

            start.countDown();

            roleChangeFuture.get(20, TimeUnit.SECONDS);
            disableFuture.get(20, TimeUnit.SECONDS);

            Long userTokenVersion =
                    jdbcTemplate.queryForObject("""
                            select token_version
                            from public.users
                            where id = ?
                            """,
                            Long.class,
                            USER_ID
                    );

            assertThat(userTokenVersion)
                    .isEqualTo(1L);

            assertThat(organizationAuthVersion())
                    .isEqualTo(1L);
        } finally {
            start.countDown();
        }
    }

    private RefreshTokenService.CreatedRefreshToken
    createLoginToken() {
        TransactionTemplate transaction =
                new TransactionTemplate(
                        transactionManager
                );

        RefreshTokenService.CreatedRefreshToken token =
                transaction.execute(status -> {
                    UserEntity user = userRepository
                            .findByIdWithRolesAndOrganization(
                                    USER_ID
                            )
                            .orElseThrow();

                    return refreshTokenService.createForLogin(
                            user,
                            request(),
                            Instant.now()
                    );
                });

        if (token == null) {
            throw new IllegalStateException(
                    "Refresh token transaction returned null"
            );
        }

        return token;
    }

    private void disableOrganization() {
        organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(false),
                superAdminPrincipal()
        );
    }

    private void enableOrganization() {
        organizationService.updateEnabled(
                ORGANIZATION_ID,
                new UpdateOrganizationEnabledRequest(true),
                superAdminPrincipal()
        );
    }

    private boolean organizationEnabled() {
        Boolean enabled = jdbcTemplate.queryForObject("""
                select enabled
                from public.organizations
                where id = ?
                """,
                Boolean.class,
                ORGANIZATION_ID
        );

        return Boolean.TRUE.equals(enabled);
    }

    private long organizationAuthVersion() {
        Long version = jdbcTemplate.queryForObject("""
                select auth_version
                from public.organizations
                where id = ?
                """,
                Long.class,
                ORGANIZATION_ID
        );

        return version == null ? -1L : version;
    }

    private long activeRefreshTokenCount() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from public.refresh_tokens token
                where token.user_id = ?
                  and token.revoked_at is null
                """,
                Long.class,
                USER_ID
        );

        return count == null ? 0L : count;
    }

    private long usableActiveRefreshTokenCount() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from public.refresh_tokens token
                join public.users app_user
                  on app_user.id = token.user_id
                join public.organizations organization
                  on organization.id =
                     app_user.organization_id
                where token.user_id = ?
                  and token.revoked_at is null
                  and app_user.enabled = true
                  and organization.enabled = true
                  and token.issued_token_version =
                      app_user.token_version
                  and token.issued_organization_auth_version =
                      organization.auth_version
                """,
                Long.class,
                USER_ID
        );

        return count == null ? 0L : count;
    }

    private HttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRemoteAddr("127.0.0.1");
        request.addHeader(
                "User-Agent",
                "organization-security-integration-test"
        );

        return request;
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                0L,
                0L,
                Set.of(
                        new org.springframework.security.core.authority
                                .SimpleGrantedAuthority(
                                "ROLE_SUPER_ADMIN"
                        )
                )
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrency barrier timeout"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Concurrency test interrupted",
                    exception
            );
        }
    }
}