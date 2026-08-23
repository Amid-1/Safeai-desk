package ru.safeai.gateway.auth.integration;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.safeai.gateway.auth.dto.LoginRequest;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.service.AuthCookieProperties;
import ru.safeai.gateway.auth.service.AuthCookieService;
import ru.safeai.gateway.auth.service.AuthEventService;
import ru.safeai.gateway.auth.service.AuthService;
import ru.safeai.gateway.auth.service.LoginSessionTransactionService;
import ru.safeai.gateway.auth.service.RefreshTokenCleanupBatchService;
import ru.safeai.gateway.auth.service.RefreshTokenCleanupJob;
import ru.safeai.gateway.auth.service.RefreshTokenCleanupProperties;
import ru.safeai.gateway.auth.service.RefreshTokenService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.AuthServiceUnavailableException;
import ru.safeai.gateway.common.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.common.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.ClientIpResolver;
import ru.safeai.gateway.common.security.JwtService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.ratelimit.LoginRateLimitService;
import ru.safeai.gateway.ratelimit.RefreshRateLimitService;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
@DataJpaTest(
        properties = {
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.open-in-view=false",
                "spring.jpa.show-sql=false"
        }
)
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("auth-postgres-it")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        RefreshTokenService.class,
        LoginSessionTransactionService.class,
        UserSecurityMutationTestService.class,
        UserSessionRevocationService.class,
        RefreshTokenCleanupBatchService.class,
        RefreshTokenCleanupJob.class,
        AuthCookieService.class,
        AuthService.class,
        AuthPostgresConcurrencyIT.TestBeans.class
})
@SuppressWarnings({
        "SqlDialectInspection",
        "SqlNoDataSourceInspection",
        "SqlResolve"
})
class AuthPostgresConcurrencyIT {

    private static final Instant NOW =
            Instant.parse("2026-07-22T12:00:00Z");

    private static final Duration RETENTION =
            Duration.ofDays(7);

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String USER_ROLE = "USER";

    private static final int CONCURRENT_CLEANUP_BATCH_SIZE = 4;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private LoginSessionTransactionService
            loginSessionTransactionService;

    @Autowired
    private UserSecurityMutationTestService
            securityMutationService;

    @Autowired
    private RefreshTokenCleanupJob cleanupJob;

    @Autowired
    private RefreshTokenCleanupBatchService cleanupBatchService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthEventService authEventService;

    @MockitoBean
    private LoginRateLimitService loginRateLimitService;

    @MockitoBean
    private RefreshRateLimitService refreshRateLimitService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private CsrfTokenRepository csrfTokenRepository;

    private final List<Seed> createdSeeds =
            new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            AuthCookieProperties.class,
            RefreshTokenCleanupProperties.class
    })
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @AfterEach
    void cleanDatabase() {
        dropCommitFailureObjects();

        for (Seed seed : createdSeeds) {
            jdbcTemplate.update(
                    "delete from refresh_tokens where user_id = ?",
                    seed.userId()
            );
            /*
             * Таблицу user_roles нельзя очищать отдельно:
             * constraint trigger запрещает оставлять пользователя
             * без роли. Удаление пользователя каскадно удалит
             * связанные строки ролей.
             */
            jdbcTemplate.update(
                    "delete from users where id = ?",
                    seed.userId()
            );
            jdbcTemplate.update(
                    "delete from organizations where id = ?",
                    seed.organizationId()
            );
        }

        createdSeeds.clear();
    }

    @Test
    void twoConcurrentRefreshes_oneSuccess_oneReuse_familyTerminated()
            throws Exception {
        Seed seed = createSeed();
        String rawToken = createLoginRefreshToken(seed);
        UUID familyId = familyIdForRawToken(rawToken);

        RaceResult<RefreshAttempt, RefreshAttempt> race = race(
                () -> rotateAttempt(rawToken, "first-refresh"),
                () -> rotateAttempt(rawToken, "second-refresh")
        );

        List<RefreshAttempt> attempts = List.of(
                race.first(),
                race.second()
        );

        assertThat(attempts)
                .filteredOn(RefreshSucceeded.class::isInstance)
                .hasSize(1);

        assertThat(attempts)
                .filteredOn(RefreshReuse.class::isInstance)
                .hasSize(1);

        assertThat(activeFamilyRows(familyId)).isZero();
        assertThat(familyReasons(familyId))
                .isNotEmpty()
                .containsOnly(
                        RefreshTokenRevocationReason
                                .REUSE_DETECTED.name()
                );
    }

    @Test
    void concurrentRefreshAndLogout_noFamilyTokenRemainsActive()
            throws Exception {
        Seed seed = createSeed();
        String rawToken = createLoginRefreshToken(seed);
        UUID familyId = familyIdForRawToken(rawToken);

        RaceResult<RefreshAttempt, Boolean> race = race(
                () -> rotateAttempt(rawToken, "refresh-vs-logout"),
                () -> {
                    refreshTokenService
                            .revokeFamilyAndReturnSubject(rawToken);
                    return true;
                }
        );

        assertThat(race.second()).isTrue();
        assertThat(race.first())
                .isInstanceOfAny(
                        RefreshSucceeded.class,
                        RefreshRejected.class
                );

        assertThat(activeFamilyRows(familyId)).isZero();
        assertThat(familyReasons(familyId))
                .isNotEmpty()
                .containsOnly(
                        RefreshTokenRevocationReason.LOGOUT.name()
                );
    }

    @ParameterizedTest
    @EnumSource(SecurityMutation.class)
    void concurrentRefreshAndSecurityMutation_noReplacementSurvives(
            SecurityMutation mutation
    ) throws Exception {
        Seed seed = createSeed();
        String rawToken = createLoginRefreshToken(seed);
        UUID familyId = familyIdForRawToken(rawToken);

        RaceResult<RefreshAttempt, Long> race = race(
                () -> rotateAttempt(
                        rawToken,
                        "refresh-vs-" + mutation.name()
                ),
                () -> applyMutation(mutation, seed.userId())
        );

        assertThat(race.first())
                .isInstanceOfAny(
                        RefreshSucceeded.class,
                        RefreshRejected.class
                );
        assertThat(race.second()).isEqualTo(1L);

        /*
         * При READ COMMITTED replacement может физически успеть
         * вставиться после начала bulk-revoke. Он всё равно должен
         * быть непригодным, потому что содержит старый security epoch.
         */
        assertThat(usableActiveFamilyRows(familyId))
                .isZero();

        assertThat(revokedFamilyReasons(familyId))
                .contains(mutation.reason().name());

        assertThat(tokenVersion(seed.userId())).isEqualTo(1L);

        if (mutation == SecurityMutation.DISABLE) {
            assertThat(userEnabled(seed.userId())).isFalse();
        }

        if (mutation == SecurityMutation.ROLE_CHANGE) {
            assertThat(roleNames(seed.userId()))
                    .containsExactly(USER_ROLE);
        }
    }

    @ParameterizedTest
    @EnumSource(SecurityMutation.class)
    void concurrentLoginAndSecurityMutation_newSessionCannotSurvive(
            SecurityMutation mutation
    ) throws Exception {
        Seed seed = createSeed();
        SafeAiUserPrincipal principal = principal(seed);

        RaceResult<LoginAttempt, Long> race = race(
                () -> loginAttempt(principal, mutation.name()),
                () -> applyMutation(mutation, seed.userId())
        );

        assertThat(race.first())
                .isInstanceOfAny(
                        LoginSucceeded.class,
                        LoginRejected.class
                );
        assertThat(race.second()).isEqualTo(1L);

        assertThat(activeUserRows(seed.userId())).isZero();
        assertThat(tokenVersion(seed.userId())).isEqualTo(1L);
    }

    @Test
    void realCommitFailure_doesNotWriteSetCookie() {
        Seed seed = createSeed();
        installDeferredCommitFailureTrigger();

        SafeAiUserPrincipal principal = principal(seed);
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        "",
                        principal.getAuthorities()
                );

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authentication);
        when(clientIpResolver.resolve(any(HttpServletRequest.class)))
                .thenReturn("127.0.0.1");

        MockHttpServletRequest request = request("FAIL_COMMIT");
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        assertThatThrownBy(() -> authService.login(
                new LoginRequest(
                        seed.email(),
                        "LegacyPassword1!"
                ),
                request,
                response
        )).isInstanceOf(AuthServiceUnavailableException.class);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .isEmpty();
        assertThat(countRefreshRowsForUser(seed.userId()))
                .isZero();

        verify(jwtService, never())
                .generateToken(any(AccessTokenSubject.class));
        verify(csrfTokenRepository, never())
                .saveToken(any(), any(), any());
    }

    @Test
    void cleanupChain_batchSizeOneDeletesPredecessorsBeforeReplacements() {
        Seed seed = createSeed();
        Instant threshold = NOW.minus(RETENTION);

        RefreshChain chain = insertExpiredRefreshChain(
                seed.userId(),
                threshold.minus(Duration.ofDays(1))
        );

        assertThat(cleanupBatchService.deleteNextBatch(
                threshold,
                1
        )).isEqualTo(1);

        assertThat(tokenExists(chain.firstId()))
                .isFalse();
        assertThat(tokenExists(chain.secondId()))
                .isTrue();
        assertThat(tokenExists(chain.thirdId()))
                .isTrue();

        assertThat(cleanupBatchService.deleteNextBatch(
                threshold,
                1
        )).isEqualTo(1);

        assertThat(tokenExists(chain.secondId()))
                .isFalse();
        assertThat(tokenExists(chain.thirdId()))
                .isTrue();

        assertThat(cleanupBatchService.deleteNextBatch(
                threshold,
                1
        )).isEqualTo(1);

        assertThat(tokenExists(chain.thirdId()))
                .isFalse();

        assertThat(cleanupBatchService.deleteNextBatch(
                threshold,
                1
        )).isZero();
    }

    @Test
    void twoCleanupWorkers_deleteExpiredReplacementChainsWithoutFkViolations()
            throws Exception {
        Seed seed = createSeed();
        Instant threshold = NOW.minus(RETENTION);

        int chains = 32;

        for (int index = 0; index < chains; index++) {
            insertExpiredRefreshChain(
                    seed.userId(),
                    threshold.minus(
                            Duration.ofDays(1)
                                    .plusSeconds(index)
                    )
            );
        }

        RaceResult<Long, Long> race = race(
                () -> deleteExpiredUntilIdle(
                        threshold
                ),
                () -> deleteExpiredUntilIdle(
                        threshold
                )
        );

        long deleted = Math.addExact(
                race.first(),
                race.second()
        );

        assertThat(deleted)
                .isEqualTo(chains * 3L);

        assertThat(countExpiredRows(threshold))
                .isZero();
    }

    @Test
    void twoCleanupInstances_deleteEachRowExactlyOnce()
            throws Exception {
        Seed seed = createSeed();
        Instant threshold = NOW.minus(RETENTION);

        insertCleanupRows(
                seed.userId(),
                2_000,
                threshold.minus(Duration.ofDays(1))
        );

        RaceResult<RefreshTokenCleanupJob.CleanupResult,
                RefreshTokenCleanupJob.CleanupResult> race = race(
                cleanupJob::runCleanup,
                cleanupJob::runCleanup
        );

        long deleted = Math.addExact(
                race.first().deletedRows(),
                race.second().deletedRows()
        );

        assertThat(deleted).isEqualTo(2_000L);
        assertThat(countExpiredRows(threshold)).isZero();
    }

    @Test
    void retentionBoundary_isStrictAndConservative() {
        Seed seed = createSeed();
        Instant threshold = NOW.minus(RETENTION);

        UUID older = insertActiveHistoricalToken(
                seed.userId(),
                threshold.minusNanos(1_000)
        );
        UUID exact = insertActiveHistoricalToken(
                seed.userId(),
                threshold
        );
        UUID newer = insertActiveHistoricalToken(
                seed.userId(),
                threshold.plusNanos(1_000)
        );

        RefreshTokenCleanupJob.CleanupResult result =
                cleanupJob.runCleanup();

        assertThat(result.deletedRows()).isEqualTo(1L);
        assertThat(tokenExists(older)).isFalse();
        assertThat(tokenExists(exact)).isTrue();
        assertThat(tokenExists(newer)).isTrue();
    }

    @Test
    void rotatedTokenEightDaysOld_isRetainedAndTriggersReuse() {
        Seed seed = createSeed();
        UUID familyId = UUID.randomUUID();
        String oldRaw = rawToken("old");
        String replacementRaw = rawToken("replacement");
        Instant familyCreatedAt = NOW.minus(Duration.ofDays(10));
        Instant familyExpiresAt = NOW.plus(Duration.ofDays(30));

        UUID oldTokenId = UUID.randomUUID();
        UUID replacementTokenId = UUID.randomUUID();

        insertToken(new TokenRow(
                replacementTokenId,
                seed.userId(),
                hash(replacementRaw),
                0L,
                0L,
                NOW.minus(Duration.ofDays(8)),
                NOW.plus(Duration.ofDays(20)),
                familyCreatedAt,
                familyExpiresAt,
                null,
                null,
                familyId,
                null,
                null
        ));

        insertToken(new TokenRow(
                oldTokenId,
                seed.userId(),
                hash(oldRaw),
                0L,
                0L,
                NOW.minus(Duration.ofDays(9)),
                NOW.plus(Duration.ofDays(10)),
                familyCreatedAt,
                familyExpiresAt,
                NOW.minus(Duration.ofDays(8)),
                RefreshTokenRevocationReason.ROTATED,
                familyId,
                replacementTokenId,
                NOW.minus(Duration.ofDays(8))
        ));

        RefreshTokenCleanupJob.CleanupResult cleanup =
                cleanupJob.runCleanup();

        assertThat(cleanup.deletedRows()).isZero();
        assertThat(familyRowCount(familyId)).isEqualTo(2L);

        assertThatThrownBy(() -> refreshTokenService.rotate(
                oldRaw,
                request("old-rotated-reuse")
        )).isInstanceOf(
                RefreshTokenReuseDetectedException.class
        );

        assertThat(activeFamilyRows(familyId)).isZero();
        assertThat(familyReasons(familyId))
                .containsOnly(
                        RefreshTokenRevocationReason
                                .REUSE_DETECTED.name()
                );
    }

    @Test
    void largeCleanup_isCommittedInBoundedBatches() {
        Seed seed = createSeed();
        Instant threshold = NOW.minus(RETENTION);

        insertCleanupRows(
                seed.userId(),
                2_503,
                threshold.minus(Duration.ofDays(1))
        );

        RefreshTokenCleanupJob.CleanupResult result =
                cleanupJob.runCleanup();

        assertThat(result.deletedRows()).isEqualTo(2_503L);
        assertThat(result.committedBatches()).isEqualTo(13);
        assertThat(result.limitReached()).isFalse();
        assertThat(countExpiredRows(threshold)).isZero();
    }

    private Seed createSeed() {
        Seed seed = inTransaction(() -> {
            RoleEntity admin = requireRole(ADMIN_ROLE);
            requireRole(USER_ROLE);

            OrganizationEntity organization =
                    new OrganizationEntity();
            organization.setId(UUID.randomUUID());
            organization.setName(
                    "Auth IT " + UUID.randomUUID()
            );
            organization.setEnabled(true);
            entityManager.persist(organization);

            UserEntity user = new UserEntity();
            user.setId(UUID.randomUUID());
            user.setOrganization(organization);
            user.setEmail(
                    "auth-it-"
                            + UUID.randomUUID()
                            + "@test.local"
            );
            user.setPasswordHash("encoded-password");
            user.setFullName("Auth Integration User");
            user.setEnabled(true);
            user.setTokenVersion(0L);
            user.setRoles(Set.of(admin));
            entityManager.persist(user);
            entityManager.flush();

            return new Seed(
                    user.getId(),
                    organization.getId(),
                    user.getEmail()
            );
        });

        createdSeeds.add(seed);
        return seed;
    }

    private RoleEntity requireRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseGet(() -> {
                    RoleEntity role = new RoleEntity();
                    role.setId(UUID.randomUUID());
                    role.setName(roleName);
                    entityManager.persist(role);
                    return role;
                });
    }

    private String createLoginRefreshToken(Seed seed) {
        return inTransaction(() -> {
            UserEntity user = userRepository
                    .findByIdForSecurityUpdate(seed.userId())
                    .orElseThrow();

            return refreshTokenService.createForLogin(
                    user,
                    request("create-login-token"),
                    NOW
            ).rawToken();
        });
    }

    private RefreshAttempt rotateAttempt(
            String rawToken,
            String userAgent
    ) {
        try {
            refreshTokenService.rotate(
                    rawToken,
                    request(userAgent)
            );
            return new RefreshSucceeded();
        } catch (RefreshTokenReuseDetectedException exception) {
            return new RefreshReuse();
        } catch (InvalidRefreshTokenException exception) {
            return new RefreshRejected();
        }
    }

    private LoginAttempt loginAttempt(
            SafeAiUserPrincipal principal,
            String suffix
    ) {
        try {
            loginSessionTransactionService.createSession(
                    principal,
                    request("login-vs-" + suffix)
            );
            return new LoginSucceeded();
        } catch (BadCredentialsException exception) {
            return new LoginRejected();
        }
    }

    private long applyMutation(
            SecurityMutation mutation,
            UUID userId
    ) {
        return switch (mutation) {
            case PASSWORD_RESET ->
                    securityMutationService.resetPassword(
                            userId,
                            "new-encoded-password"
                    );
            case ROLE_CHANGE ->
                    securityMutationService.replaceRole(
                            userId,
                            USER_ROLE
                    );
            case DISABLE ->
                    securityMutationService.disableUser(userId);
        };
    }

    private SafeAiUserPrincipal principal(Seed seed) {
        return SafeAiUserPrincipal.passwordPrincipal(
                seed.userId(),
                seed.organizationId(),
                seed.email(),
                "encoded-password",
                true,
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_" + ADMIN_ROLE
                        )
                )
        );
    }

    private MockHttpServletRequest request(String userAgent) {
        MockHttpServletRequest request =
                new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", userAgent);
        return request;
    }

    private <A, B> RaceResult<A, B> race(
            Callable<A> first,
            Callable<B> second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {
            Future<A> firstFuture = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(20, TimeUnit.SECONDS))
                        .isTrue();
                return first.call();
            });

            Future<B> secondFuture = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(20, TimeUnit.SECONDS))
                        .isTrue();
                return second.call();
            });

            assertThat(ready.await(20, TimeUnit.SECONDS))
                    .isTrue();
            start.countDown();

            return new RaceResult<>(
                    firstFuture.get(30, TimeUnit.SECONDS),
                    secondFuture.get(30, TimeUnit.SECONDS)
            );
        }
    }

    private <T> T inTransaction(Callable<T> action) {
        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        return template.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private UUID familyIdForRawToken(String rawToken) {
        return jdbcTemplate.queryForObject(
                "select token_family_id from refresh_tokens "
                        + "where token_hash = ?",
                UUID.class,
                hash(rawToken)
        );
    }

    private long activeFamilyRows(UUID familyId) {
        return queryLong(
                "select count(*) from refresh_tokens "
                        + "where token_family_id = ? "
                        + "and revoked_at is null",
                familyId
        );
    }

    private long usableActiveFamilyRows(
            UUID familyId
    ) {
        return queryLong(
                """
                select count(*)
                from refresh_tokens token
                join users app_user
                  on app_user.id = token.user_id
                join organizations organization
                  on organization.id =
                     app_user.organization_id
                where token.token_family_id = ?
                  and token.revoked_at is null
                  and app_user.enabled = true
                  and organization.enabled = true
                  and token.issued_token_version =
                      app_user.token_version
                  and token.issued_organization_auth_version =
                      organization.auth_version
                """,
                familyId
        );
    }

    private long activeUserRows(UUID userId) {
        return queryLong(
                "select count(*) from refresh_tokens "
                        + "where user_id = ? and revoked_at is null",
                userId
        );
    }

    private long familyRowCount(UUID familyId) {
        return queryLong(
                "select count(*) from refresh_tokens "
                        + "where token_family_id = ?",
                familyId
        );
    }

    private long countRefreshRowsForUser(UUID userId) {
        return queryLong(
                "select count(*) from refresh_tokens "
                        + "where user_id = ?",
                userId
        );
    }

    private long countExpiredRows(Instant threshold) {
        return queryLong(
                "select count(*) from refresh_tokens "
                        + "where family_expires_at < ?",
                Timestamp.from(threshold)
        );
    }

    private long queryLong(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments
        );
        return Objects.requireNonNull(value);
    }

    private List<String> familyReasons(UUID familyId) {
        return jdbcTemplate.queryForList(
                "select revocation_reason from refresh_tokens "
                        + "where token_family_id = ? "
                        + "order by created_at, id",
                String.class,
                familyId
        );
    }

    private List<String> revokedFamilyReasons(
            UUID familyId
    ) {
        return jdbcTemplate.queryForList(
                """
                select revocation_reason
                from refresh_tokens
                where token_family_id = ?
                  and revoked_at is not null
                  and revocation_reason is not null
                order by created_at, id
                """,
                String.class,
                familyId
        );
    }

    private long tokenVersion(UUID userId) {
        return queryLong(
                "select token_version from users where id = ?",
                userId
        );
    }

    private boolean userEnabled(UUID userId) {
        Boolean value = jdbcTemplate.queryForObject(
                "select enabled from users where id = ?",
                Boolean.class,
                userId
        );
        return Boolean.TRUE.equals(value);
    }

    private List<String> roleNames(UUID userId) {
        return jdbcTemplate.queryForList(
                "select role.name "
                        + "from roles role "
                        + "join user_roles user_role "
                        + "on user_role.role_id = role.id "
                        + "where user_role.user_id = ? "
                        + "order by role.name",
                String.class,
                userId
        );
    }

    private boolean tokenExists(UUID tokenId) {
        return queryLong(
                "select count(*) from refresh_tokens where id = ?",
                tokenId
        ) == 1L;
    }

    private long deleteExpiredUntilIdle(
            Instant threshold
    ) {
        long deletedRows = 0L;

        for (int attempt = 0;
             attempt < 10_000;
             attempt++) {

            int deleted =
                    cleanupBatchService
                            .deleteNextBatch(
                                    threshold,
                                    CONCURRENT_CLEANUP_BATCH_SIZE
                            );

            if (deleted == 0) {
                return deletedRows;
            }

            deletedRows =
                    Math.addExact(
                            deletedRows,
                            deleted
                    );
        }

        throw new IllegalStateException(
                "Cleanup worker did not become idle"
        );
    }

    private RefreshChain insertExpiredRefreshChain(
            UUID userId,
            Instant familyExpiresAt
    ) {
        UUID familyId = UUID.randomUUID();

        Instant familyCreatedAt =
                familyExpiresAt.minus(
                        Duration.ofDays(90)
                );

        Instant firstCreatedAt =
                familyCreatedAt.plus(
                        Duration.ofHours(1)
                );

        Instant secondCreatedAt =
                firstCreatedAt.plus(
                        Duration.ofHours(1)
                );

        Instant thirdCreatedAt =
                secondCreatedAt.plus(
                        Duration.ofHours(1)
                );

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID thirdId = UUID.randomUUID();

        /*
         * FK immediate: сначала вставляем replacement C,
         * затем B -> C и только потом A -> B.
         */
        insertToken(new TokenRow(
                thirdId,
                userId,
                hash(rawToken("cleanup-chain-c")),
                0L,
                0L,
                thirdCreatedAt,
                thirdCreatedAt.plus(
                        Duration.ofDays(30)
                ),
                familyCreatedAt,
                familyExpiresAt,
                null,
                null,
                familyId,
                null,
                null
        ));

        insertToken(new TokenRow(
                secondId,
                userId,
                hash(rawToken("cleanup-chain-b")),
                0L,
                0L,
                secondCreatedAt,
                secondCreatedAt.plus(
                        Duration.ofDays(30)
                ),
                familyCreatedAt,
                familyExpiresAt,
                secondCreatedAt.plusSeconds(1),
                RefreshTokenRevocationReason.ROTATED,
                familyId,
                thirdId,
                null
        ));

        insertToken(new TokenRow(
                firstId,
                userId,
                hash(rawToken("cleanup-chain-a")),
                0L,
                0L,
                firstCreatedAt,
                firstCreatedAt.plus(
                        Duration.ofDays(30)
                ),
                familyCreatedAt,
                familyExpiresAt,
                firstCreatedAt.plusSeconds(1),
                RefreshTokenRevocationReason.ROTATED,
                familyId,
                secondId,
                null
        ));

        return new RefreshChain(
                firstId,
                secondId,
                thirdId
        );
    }

    private void insertCleanupRows(
            UUID userId,
            int count,
            Instant familyExpiresAt
    ) {
        List<TokenRow> rows = new ArrayList<>(count);

        for (int index = 0; index < count; index++) {
            String raw = rawToken("cleanup" + index);
            Instant familyCreatedAt =
                    familyExpiresAt.minus(Duration.ofDays(90));
            Instant createdAt =
                    familyCreatedAt.plus(Duration.ofHours(1));
            Instant expiresAt =
                    familyCreatedAt.plus(Duration.ofDays(30));

            rows.add(new TokenRow(
                    UUID.randomUUID(),
                    userId,
                    hash(raw),
                    0L,
                    0L,
                    createdAt,
                    expiresAt,
                    familyCreatedAt,
                    familyExpiresAt,
                    null,
                    null,
                    UUID.randomUUID(),
                    null,
                    null
            ));
        }

        batchInsertTokens(rows);
    }

    private UUID insertActiveHistoricalToken(
            UUID userId,
            Instant familyExpiresAt
    ) {
        UUID id = UUID.randomUUID();
        Instant familyCreatedAt =
                familyExpiresAt.minus(Duration.ofDays(90));
        Instant createdAt =
                familyCreatedAt.plus(Duration.ofHours(1));
        Instant expiresAt =
                familyCreatedAt.plus(Duration.ofDays(30));

        insertToken(new TokenRow(
                id,
                userId,
                hash(rawToken("boundary")),
                0L,
                0L,
                createdAt,
                expiresAt,
                familyCreatedAt,
                familyExpiresAt,
                null,
                null,
                UUID.randomUUID(),
                null,
                null
        ));

        return id;
    }

    private void insertToken(TokenRow row) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection
                    .prepareStatement(INSERT_TOKEN_SQL);
            bindToken(statement, row);
            return statement;
        });
    }

    private void batchInsertTokens(List<TokenRow> rows) {
        jdbcTemplate.batchUpdate(
                INSERT_TOKEN_SQL,
                rows,
                500,
                this::bindToken
        );
    }

    private void bindToken(
            PreparedStatement statement,
            TokenRow row
    ) throws SQLException {
        statement.setObject(1, row.id());
        statement.setObject(2, row.userId());
        statement.setString(3, row.tokenHash());
        statement.setLong(4, row.issuedTokenVersion());
        statement.setLong(
                5,
                row.issuedOrganizationAuthVersion()
        );
        statement.setTimestamp(
                6,
                Timestamp.from(row.expiresAt())
        );
        statement.setTimestamp(
                7,
                Timestamp.from(row.familyCreatedAt())
        );
        statement.setTimestamp(
                8,
                Timestamp.from(row.familyExpiresAt())
        );

        if (row.revokedAt() == null) {
            statement.setNull(9, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(
                    9,
                    Timestamp.from(row.revokedAt())
            );
        }

        if (row.reason() == null) {
            statement.setNull(10, Types.VARCHAR);
        } else {
            statement.setString(
                    10,
                    row.reason().name()
            );
        }

        statement.setTimestamp(
                11,
                Timestamp.from(row.createdAt())
        );
        statement.setString(12, "127.0.0.1");
        statement.setString(
                13,
                "AuthPostgresConcurrencyIT"
        );
        statement.setObject(14, row.familyId());

        if (row.replacedByTokenId() == null) {
            statement.setNull(15, Types.OTHER);
        } else {
            statement.setObject(
                    15,
                    row.replacedByTokenId()
            );
        }

        if (row.lastUsedAt() == null) {
            statement.setNull(16, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(
                    16,
                    Timestamp.from(row.lastUsedAt())
            );
        }
    }

    private void installDeferredCommitFailureTrigger() {
        dropCommitFailureObjects();

        jdbcTemplate.execute("""
                create table auth_test_commit_parent (
                    id integer primary key
                )
                """);

        jdbcTemplate.execute("""
                create table auth_test_commit_child (
                    token_id uuid primary key,
                    parent_id integer not null,
                    constraint auth_test_commit_fk
                        foreign key (parent_id)
                        references auth_test_commit_parent(id)
                        deferrable initially deferred
                )
                """);

        jdbcTemplate.execute("""
                create function auth_test_fail_login_commit()
                returns trigger
                language plpgsql
                as $$
                begin
                    if new.user_agent = 'FAIL_COMMIT' then
                        insert into auth_test_commit_child(
                            token_id,
                            parent_id
                        ) values (
                            new.id,
                            999
                        );
                    end if;
                    return new;
                end;
                $$
                """);

        jdbcTemplate.execute("""
                create trigger auth_test_fail_login_commit_trigger
                after insert on refresh_tokens
                for each row
                execute function auth_test_fail_login_commit()
                """);
    }

    private void dropCommitFailureObjects() {
        jdbcTemplate.execute("""
                drop trigger if exists
                    auth_test_fail_login_commit_trigger
                    on refresh_tokens
                """);
        jdbcTemplate.execute("""
                drop function if exists
                    auth_test_fail_login_commit()
                """);
        jdbcTemplate.execute("""
                drop table if exists auth_test_commit_child
                """);
        jdbcTemplate.execute("""
                drop table if exists auth_test_commit_parent
                """);
    }

    private String rawToken(String prefix) {
        return prefix
                + "_"
                + UUID.randomUUID()
                .toString()
                .replace("-", "");
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(
                            rawToken.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final String INSERT_TOKEN_SQL = """
            insert into refresh_tokens (
                id,
                user_id,
                token_hash,
                issued_token_version,
                issued_organization_auth_version,
                expires_at,
                family_created_at,
                family_expires_at,
                revoked_at,
                revocation_reason,
                created_at,
                created_by_ip,
                user_agent,
                token_family_id,
                replaced_by_token_id,
                last_used_at
            ) values (
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?
            )
            """;

    private enum SecurityMutation {
        PASSWORD_RESET(
                RefreshTokenRevocationReason.PASSWORD_RESET
        ),
        ROLE_CHANGE(
                RefreshTokenRevocationReason.ROLE_CHANGED
        ),
        DISABLE(
                RefreshTokenRevocationReason.USER_DISABLED
        );

        private final RefreshTokenRevocationReason reason;

        SecurityMutation(
                RefreshTokenRevocationReason reason
        ) {
            this.reason = reason;
        }

        RefreshTokenRevocationReason reason() {
            return reason;
        }
    }

    private sealed interface RefreshAttempt
            permits RefreshSucceeded,
            RefreshReuse,
            RefreshRejected {
    }

    private record RefreshSucceeded()
            implements RefreshAttempt {
    }

    private record RefreshReuse()
            implements RefreshAttempt {
    }

    private record RefreshRejected()
            implements RefreshAttempt {
    }

    private sealed interface LoginAttempt
            permits LoginSucceeded, LoginRejected {
    }

    private record LoginSucceeded()
            implements LoginAttempt {
    }

    private record LoginRejected()
            implements LoginAttempt {
    }

    private record RaceResult<A, B>(
            A first,
            B second
    ) {
    }

    private record Seed(
            UUID userId,
            UUID organizationId,
            String email
    ) {
    }

    private record RefreshChain(
            UUID firstId,
            UUID secondId,
            UUID thirdId
    ) {
    }

    private record TokenRow(
            UUID id,
            UUID userId,
            String tokenHash,
            long issuedTokenVersion,
            long issuedOrganizationAuthVersion,
            Instant createdAt,
            Instant expiresAt,
            Instant familyCreatedAt,
            Instant familyExpiresAt,
            Instant revokedAt,
            RefreshTokenRevocationReason reason,
            UUID familyId,
            UUID replacedByTokenId,
            Instant lastUsedAt
    ) {
    }
}
