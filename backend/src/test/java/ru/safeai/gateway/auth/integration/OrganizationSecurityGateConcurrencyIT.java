package ru.safeai.gateway.auth.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.auth.service.LoginSessionResult;
import ru.safeai.gateway.auth.service.LoginSessionTransactionService;
import ru.safeai.gateway.auth.service.RefreshTokenService;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL/Testcontainers concurrency regression for organization ->
 * user -> token security locking. The base test support owns the test DB.
 * Avoid @Transactional on test methods: concurrent sessions MUST be isolated.
 */
@Tag("integration")
@SpringBootTest(properties = "safeai.usage.rollup.enabled=false")
@ActiveProfiles("test")
class OrganizationSecurityGateConcurrencyIT extends AbstractPostgresIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private LoginSessionTransactionService loginSessions;
    @Autowired private RefreshTokenService refreshTokens;

    private record Fixture(UUID organizationId, UUID userId, String email) {
        SafeAiUserPrincipal principal() {
            return SafeAiUserPrincipal.passwordPrincipal(
                    userId, organizationId, email, "encoded-password", true, 0L, 0L,
                    Set.of(new SimpleGrantedAuthority("ROLE_USER")));
        }
    }

    private Fixture fixture() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String email = "lock-" + userId + "@test.example";
        String organizationName = "Tenant " + organizationId;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO public.organizations
                        (id, name, normalized_name, enabled,
                         created_at, updated_at, version, auth_version)
                    VALUES (?, ?, public.normalize_organization_name(?), true,
                            now(), now(), 0, 0)
                    """, organizationId, organizationName, organizationName);
            jdbc.update("""
                    INSERT INTO public.users
                        (id, organization_id, email, password_hash, full_name,
                         enabled, created_at, updated_at, token_version, version)
                    VALUES (?, ?, ?, 'encoded-password', 'Lock Test',
                            true, now(), now(), 0, 0)
                    """, userId, organizationId, email);
            jdbc.update("""
                    INSERT INTO public.user_roles (user_id, role_id)
                    SELECT ?, id FROM public.roles WHERE name = 'USER'
                    """, userId);
        });
        return new Fixture(organizationId, userId, email);
    }

    private HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "SecurityGateIT");
        return request;
    }

    private void disableInConnection(Connection connection, UUID organizationId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE public.organizations
                   SET enabled = false, auth_version = auth_version + 1
                 WHERE id = ?
                """)) {
            statement.setObject(1, organizationId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(30)
    void loginWaitingForDisableCannotIssueSessionAfterDisableCommit() throws Exception {
        Fixture f = fixture();
        try (Connection disableTx = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            disableTx.setAutoCommit(false);
            try (PreparedStatement lock = disableTx.prepareStatement(
                    "SELECT id FROM public.organizations WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, f.organizationId());
                lock.executeQuery().close();
            }
            CountDownLatch entered = new CountDownLatch(1);
            var pendingLogin = pool.submit(() -> {
                entered.countDown();
                return loginSessions.createSession(f.principal(), request());
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Disable commits BEFORE the login security gate can be acquired.
            disableInConnection(disableTx, f.organizationId());
            disableTx.commit();
            assertThatThrownBy(() -> pendingLogin.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadCredentialsException.class);
            Long issued = jdbc.queryForObject("""
                    SELECT count(*) FROM public.refresh_tokens WHERE user_id = ?
                    """, Long.class, f.userId());
            assertThat(issued).isZero();
        }
    }

    @Test
    @Timeout(30)
    void alreadyIssuedSessionFailsRefreshAfterDisableEpochCommit() throws Exception {
        Fixture f = fixture();
        LoginSessionResult login = loginSessions.createSession(f.principal(), request());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            disableInConnection(connection, f.organizationId());
            connection.commit();
        }
        assertThatThrownBy(() -> refreshTokens.rotate(login.rawRefreshToken(), request()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @Timeout(30)
    void refreshWaitingForDisableCannotRotateAfterDisableCommit() throws Exception {
        Fixture f = fixture();
        LoginSessionResult login = loginSessions.createSession(f.principal(), request());
        try (Connection disableTx = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            disableTx.setAutoCommit(false);
            try (PreparedStatement lock = disableTx.prepareStatement(
                    "SELECT id FROM public.organizations WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, f.organizationId());
                lock.executeQuery().close();
            }
            CountDownLatch entered = new CountDownLatch(1);
            var pendingRefresh = pool.submit(() -> {
                entered.countDown();
                return refreshTokens.rotate(login.rawRefreshToken(), request());
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            disableInConnection(disableTx, f.organizationId());
            disableTx.commit();
            assertThatThrownBy(() -> pendingRefresh.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidRefreshTokenException.class);
            Long issued = jdbc.queryForObject("""
                    SELECT count(*) FROM public.refresh_tokens WHERE user_id = ?
                    """, Long.class, f.userId());
            // The predecessor survives for security evidence; no successor.
            assertThat(issued).isEqualTo(1);
        }
    }

    @Test
    @Timeout(30)
    void organizationExclusiveLockWaitsForSharedAuthenticationGate() throws Exception {
        Fixture f = fixture();
        try (Connection authTx = dataSource.getConnection();
             Connection disableTx = dataSource.getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            authTx.setAutoCommit(false);
            disableTx.setAutoCommit(false);
            try (PreparedStatement statement = authTx.prepareStatement(
                    "SELECT id FROM public.organizations WHERE id = ? FOR SHARE")) {
                statement.setObject(1, f.organizationId());
                statement.executeQuery().close();
            }
            CountDownLatch entered = new CountDownLatch(1);
            var disabling = pool.submit(() -> {
                entered.countDown();
                disableInConnection(disableTx, f.organizationId());
                disableTx.commit();
                return true;
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Must not claim SQL-level fairness/timing, just prove successful
            // completion after release without PostgreSQL deadlock/lock timeout.
            authTx.commit();
            assertThat(disabling.get(10, TimeUnit.SECONDS)).isTrue();
            Boolean enabled = jdbc.queryForObject(
                    "SELECT enabled FROM public.organizations WHERE id = ?",
                    Boolean.class, f.organizationId());
            assertThat(enabled).isFalse();
        }
    }

    @Test
    @Timeout(30)
    void concurrentRefreshOfOneTokenDoesNotCreateTwoSuccessors() throws Exception {
        Fixture f = fixture();
        LoginSessionResult login = loginSessions.createSession(f.principal(), request());
        // A single predecessor may be rotated only once. Depending on racing
        // order, the loser sees ROTATED/reuse or already revoked; both deny.
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> rotationResult(login.rawRefreshToken(), start));
            var second = pool.submit(() -> rotationResult(login.rawRefreshToken(), start));
            start.countDown();
            boolean success1 = first.get(15, TimeUnit.SECONDS);
            boolean success2 = second.get(15, TimeUnit.SECONDS);
            assertThat(success1 != success2).isTrue();
            Long tokenCount = jdbc.queryForObject("""
                    SELECT count(*) FROM public.refresh_tokens WHERE user_id = ?
                    """, Long.class, f.userId());
            assertThat(tokenCount).isEqualTo(2);
        }
    }

    private boolean rotationResult(String rawToken, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            refreshTokens.rotate(rawToken, request());
            return true;
        } catch (InvalidRefreshTokenException
                 | RefreshTokenReuseDetectedException exception) {
            return false;
        }
    }
}