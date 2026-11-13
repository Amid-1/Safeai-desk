package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisFixedWindowRateLimiterIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse(
                            "redis:7.4-alpine"
                    )
            ).withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory
            connectionFactory;

    private static StringRedisTemplate
            redisTemplate;

    private static RedisFixedWindowRateLimiter
            rateLimiter;

    @BeforeAll
    static void startRedisClient() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(
                        REDIS.getHost(),
                        REDIS.getMappedPort(REDIS_PORT)
                );

        connectionFactory =
                new LettuceConnectionFactory(
                        configuration
                );

        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        redisTemplate =
                new StringRedisTemplate(
                        connectionFactory
                );

        redisTemplate.afterPropertiesSet();

        rateLimiter =
                new RedisFixedWindowRateLimiter(
                        redisTemplate
                );
    }

    @AfterAll
    static void stopRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void flushRedis() {
        try (var connection =
                     connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void firstNAttemptsAllowedAndNPlusOneBlockedAtomically() {
        LoginKeys keys =
                loginKeys("first-n");

        for (int attempt = 1;
             attempt <= 3;
             attempt++) {
            DualRateLimitResult result =
                    loginAttempt(
                            keys,
                            3,
                            100
                    );

            assertThat(result.allowed())
                    .isTrue();

            assertThat(result.firstCount())
                    .isEqualTo(attempt);

            assertThat(result.secondCount())
                    .isEqualTo(attempt);
        }

        DualRateLimitResult blocked =
                loginAttempt(
                        keys,
                        3,
                        100
                );

        assertThat(blocked.decision())
                .isEqualTo(
                        RateLimitDecision.FIRST_EXCEEDED
                );

        assertThat(blocked.firstCount())
                .isEqualTo(4);

        assertThat(blocked.secondCount())
                .isEqualTo(4);

        assertThat(blocked.firstTtlSeconds())
                .isPositive();

        assertThat(blocked.secondTtlSeconds())
                .isPositive();

        assertThat(value(keys.emailKey()))
                .isEqualTo(4);

        assertThat(value(keys.ipKey()))
                .isEqualTo(4);
    }

    @Test
    void compensationPreservesPriorFailuresAndConsumesReceiptOnce() {
        LoginKeys keys = loginKeys("compensation");
        loginAttempt(keys, 10, 10);
        DualRateLimitResult accepted = loginAttempt(keys, 10, 10);

        compensate(keys, accepted);
        compensate(keys, accepted);

        assertThat(value(keys.emailKey())).isEqualTo(1);
        assertThat(value(keys.ipKey())).isEqualTo(1);
    }

    @Test
    void compensationPreservesFailuresOfAnotherUserBehindSameNat() {
        LoginKeys failed = loginKeys("shared-nat");
        String otherEmail = failed.emailKey() + ":other";
        LoginKeys successful = new LoginKeys(
                otherEmail, failed.ipKey(), otherEmail + ":exceeded", failed.ipMarkerKey());
        loginAttempt(failed, 10, 10);
        DualRateLimitResult accepted = loginAttempt(successful, 10, 10);

        compensate(successful, accepted);

        assertThat(value(failed.emailKey())).isEqualTo(1);
        assertThat(value(successful.emailKey())).isZero();
        assertThat(value(failed.ipKey())).isEqualTo(1);
    }

    @Test
    void compensationIgnoresReplacementWindowButDecrementsStillActiveWindow() {
        LoginKeys keys = loginKeys("replacement-window");
        DualRateLimitResult accepted = loginAttempt(keys, 10, 10);

        // Expire the email generation and counter as Redis does at the window boundary.
        redisTemplate.expire(keys.emailKey(), Duration.ZERO);
        redisTemplate.expire(RedisFixedWindowRateLimiter.generationKey(keys.emailKey()), Duration.ZERO);
        DualRateLimitResult replacement = loginAttempt(keys, 10, 10);
        assertThat(replacement.allowed()).isTrue();

        compensate(keys, accepted);

        assertThat(value(keys.emailKey())).isEqualTo(1);
        assertThat(value(keys.ipKey())).isEqualTo(1);
    }

    @Test
    void staleMarkerOwnerCannotReleaseReplacementMarker() {
        LoginKeys keys = loginKeys("marker-ownership");
        loginAttempt(keys, 1, 10);
        DualRateLimitResult old = loginAttempt(keys, 1, 10);
        redisTemplate.expire(keys.emailMarkerKey(), Duration.ZERO);
        DualRateLimitResult replacement = loginAttempt(keys, 1, 10);
        assertThat(replacement.firstExceededNotification()).isTrue();

        rateLimiter.releaseNotificationMarkers(old, keys.emailMarkerKey(), keys.ipMarkerKey());

        assertThat(redisTemplate.opsForValue().get(keys.emailMarkerKey()))
                .isEqualTo(replacement.operationId().toString());
    }

    private void compensate(LoginKeys keys, DualRateLimitResult accepted) {
        rateLimiter.compensateLogin(new LoginRateLimitService.Attempt(
                keys.emailKey(), keys.ipKey(), accepted.operationId()));
    }

    @Test
    void oneHundredSuccessfulUsersBehindNatDoNotExhaustIpLimit() {
        String ipKey =
                "rate-limit:{login}:ip:nat";

        String ipMarker =
                ipKey + ":exceeded";

        for (int index = 0;
             index < 100;
             index++) {
            String emailKey =
                    "rate-limit:{login}:email:user-"
                            + index;

            LoginKeys keys =
                    new LoginKeys(
                            emailKey,
                            ipKey,
                            emailKey + ":exceeded",
                            ipMarker
                    );

            DualRateLimitResult result =
                    loginAttempt(
                            keys,
                            1,
                            10
                    );

            assertThat(result.allowed())
                    .isTrue();

            compensate(keys, result);
        }

        assertThat(value(ipKey)).isZero();

        LoginKeys nextUser =
                new LoginKeys(
                        "rate-limit:{login}:email:user-101",
                        ipKey,
                        "rate-limit:{login}:email:user-101:exceeded",
                        ipMarker
                );

        assertThat(
                loginAttempt(
                        nextUser,
                        1,
                        10
                ).allowed()
        ).isTrue();
    }

    @Test
    void exceededNotificationIsCreatedOnlyOncePerDimensionAndWindow() {
        LoginKeys keys =
                loginKeys("marker-once");

        assertThat(
                loginAttempt(
                        keys,
                        1,
                        100
                ).allowed()
        ).isTrue();

        DualRateLimitResult firstRejected =
                loginAttempt(
                        keys,
                        1,
                        100
                );

        DualRateLimitResult nextRejected =
                loginAttempt(
                        keys,
                        1,
                        100
                );

        assertThat(
                firstRejected
                        .firstExceededNotification()
        ).isTrue();

        assertThat(
                nextRejected
                        .firstExceededNotification()
        ).isFalse();

        assertThat(
                firstRejected
                        .secondExceededNotification()
        ).isFalse();
    }

    @Test
    void exhaustedOrganizationDoesNotConsumeNewUserCounter() {
        String organizationKey =
                "rate-limit:{ai-org}:organization";

        AiKeys firstUser =
                aiKeys(
                        "user-1",
                        organizationKey
                );

        AiKeys secondUser =
                aiKeys(
                        "user-2",
                        organizationKey
                );

        assertThat(
                aiAttempt(
                        firstUser,
                        10,
                        1
                ).allowed()
        ).isTrue();

        DualRateLimitResult rejected =
                aiAttempt(
                        secondUser,
                        10,
                        1
                );

        assertThat(rejected.decision())
                .isEqualTo(
                        RateLimitDecision.SECOND_EXCEEDED
                );

        assertThat(rejected.firstCount())
                .isZero();

        assertThat(rejected.secondCount())
                .isEqualTo(1);

        /*
         * The first Redis key does not exist because the rejected
         * request must not consume the new user's counter.
         *
         * The Lua response must still contain a normalized positive
         * TTL so the Java-side result protocol remains valid.
         */
        assertThat(rejected.firstTtlSeconds())
                .isPositive();

        assertThat(rejected.secondTtlSeconds())
                .isPositive();

        assertThat(
                redisTemplate.hasKey(
                        secondUser.userKey()
                )
        ).isFalse();

        assertThat(
                value(organizationKey)
        ).isEqualTo(1);
    }

    @Test
    void exhaustedUserDoesNotConsumeOrganizationCounter() {
        AiKeys keys =
                aiKeys(
                        "single-user",
                        "rate-limit:{ai-org}:organization"
                );

        assertThat(
                aiAttempt(
                        keys,
                        1,
                        10
                ).allowed()
        ).isTrue();

        DualRateLimitResult rejected =
                aiAttempt(
                        keys,
                        1,
                        10
                );

        assertThat(rejected.decision())
                .isEqualTo(
                        RateLimitDecision.FIRST_EXCEEDED
                );

        assertThat(rejected.firstCount())
                .isEqualTo(1);

        assertThat(rejected.secondCount())
                .isEqualTo(1);

        assertThat(rejected.firstTtlSeconds())
                .isPositive();

        assertThat(rejected.secondTtlSeconds())
                .isPositive();

        assertThat(
                value(
                        keys.organizationKey()
                )
        ).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCompetingForLastSlotAllowOnlyOne()
            throws Exception {
        AiKeys keys =
                aiKeys(
                        "concurrent-user",
                        "rate-limit:{ai-org}:organization"
                );

        int workers = 2;

        CountDownLatch ready =
                new CountDownLatch(workers);

        CountDownLatch start =
                new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(workers)) {
            List<Future<DualRateLimitResult>> futures =
                    new ArrayList<>();

            for (int index = 0;
                 index < workers;
                 index++) {
                futures.add(
                        executor.submit(() -> {
                            ready.countDown();

                            if (!start.await(
                                    5,
                                    TimeUnit.SECONDS
                            )) {
                                throw new IllegalStateException(
                                        "Concurrent test start timeout"
                                );
                            }

                            return aiAttempt(
                                    keys,
                                    1,
                                    1
                            );
                        })
                );
            }

            assertThat(
                    ready.await(
                            5,
                            TimeUnit.SECONDS
                    )
            )
                    .as(
                            "Both concurrent workers "
                                    + "must become ready"
                    )
                    .isTrue();

            start.countDown();

            List<DualRateLimitResult> results =
                    new ArrayList<>();

            for (Future<DualRateLimitResult> future
                    : futures) {
                results.add(
                        future.get(
                                10,
                                TimeUnit.SECONDS
                        )
                );
            }

            assertThat(results)
                    .filteredOn(
                            DualRateLimitResult::allowed
                    )
                    .hasSize(1);

            assertThat(results)
                    .filteredOn(result ->
                            !result.allowed()
                    )
                    .hasSize(1);

            assertThat(
                    value(keys.userKey())
            ).isEqualTo(1);

            assertThat(
                    value(
                            keys.organizationKey()
                    )
            ).isEqualTo(1);
        }
    }

    @Test
    void scriptsRepairPermanentCounterKeysAndAllCreatedKeysHaveTtl() {
        LoginKeys login =
                loginKeys("ttl-repair");

        redisTemplate.opsForValue().set(
                login.emailKey(),
                "1"
        );

        redisTemplate.opsForValue().set(
                login.ipKey(),
                "1"
        );

        DualRateLimitResult result =
                loginAttempt(
                        login,
                        1,
                        1
                );

        assertThat(result.decision())
                .isEqualTo(
                        RateLimitDecision.BOTH_EXCEEDED
                );

        assertThat(result.firstTtlSeconds())
                .isPositive();

        assertThat(result.secondTtlSeconds())
                .isPositive();

        assertPositiveTtl(login.emailKey());
        assertPositiveTtl(login.ipKey());
        assertPositiveTtl(login.emailMarkerKey());
        assertPositiveTtl(login.ipMarkerKey());
    }

    @Test
    void releaseNotificationMarkersDeletesOnlyMarkersCreatedByResult() {
        LoginKeys keys =
                loginKeys("release-marker");

        assertThat(
                loginAttempt(
                        keys,
                        1,
                        100
                ).allowed()
        ).isTrue();

        DualRateLimitResult rejected =
                loginAttempt(
                        keys,
                        1,
                        100
                );

        assertThat(
                redisTemplate.hasKey(
                        keys.emailMarkerKey()
                )
        ).isTrue();

        rateLimiter.releaseNotificationMarkers(
                rejected,
                keys.emailMarkerKey(),
                keys.ipMarkerKey()
        );

        assertThat(
                redisTemplate.hasKey(
                        keys.emailMarkerKey()
                )
        ).isFalse();

        assertThat(
                redisTemplate.hasKey(
                        keys.emailKey()
                )
        ).isTrue();
    }

    private DualRateLimitResult loginAttempt(
            LoginKeys keys,
            int emailLimit,
            int ipLimit
    ) {
        return rateLimiter.incrementBothAndCheck(
                keys.emailKey(),
                keys.emailMarkerKey(),
                emailLimit,
                keys.ipKey(),
                keys.ipMarkerKey(),
                ipLimit,
                Duration.ofMinutes(10)
        );
    }

    private DualRateLimitResult aiAttempt(
            AiKeys keys,
            int userLimit,
            int organizationLimit
    ) {
        return rateLimiter.tryIncrementBoth(
                keys.userKey(),
                keys.userMarkerKey(),
                userLimit,
                keys.organizationKey(),
                keys.organizationMarkerKey(),
                organizationLimit,
                Duration.ofHours(1)
        );
    }

    private LoginKeys loginKeys(
            String suffix
    ) {
        String email =
                "rate-limit:{login}:email:"
                        + suffix;

        String ip =
                "rate-limit:{login}:ip:"
                        + suffix;

        return new LoginKeys(
                email,
                ip,
                email + ":exceeded",
                ip + ":exceeded"
        );
    }

    private AiKeys aiKeys(
            String userSuffix,
            String organizationKey
    ) {
        String user =
                "rate-limit:{ai-org}:user:"
                        + userSuffix;

        return new AiKeys(
                user,
                organizationKey,
                user + ":exceeded",
                organizationKey + ":exceeded"
        );
    }

    private long value(
            String key
    ) {
        String value =
                redisTemplate.opsForValue()
                        .get(key);

        if (value == null) {
            throw new AssertionError(
                    "Redis key not found: "
                            + key
            );
        }

        return Long.parseLong(value);
    }

    private void assertPositiveTtl(
            String key
    ) {
        Long ttl =
                redisTemplate.getExpire(
                        key,
                        TimeUnit.MILLISECONDS
                );

        assertThat(ttl)
                .as("TTL for %s", key)
                .isNotNull()
                .isPositive();
    }

    private record LoginKeys(
            String emailKey,
            String ipKey,
            String emailMarkerKey,
            String ipMarkerKey
    ) {
    }

    private record AiKeys(
            String userKey,
            String organizationKey,
            String userMarkerKey,
            String organizationMarkerKey
    ) {
    }
}