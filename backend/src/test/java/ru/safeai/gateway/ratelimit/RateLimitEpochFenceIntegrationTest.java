package ru.safeai.gateway.ratelimit;

import com.github.dockerjava.api.model.ExposedPort;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@Timeout(120)
class RateLimitEpochFenceIntegrationTest {
    @Container
    @SuppressWarnings("resource") // JUnit owns the container lifecycle.
    final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always",
                    "--no-appendfsync-on-rewrite", "no", "--aof-load-truncated", "no",
                    "--maxmemory", "192mb", "--maxmemory-policy", "noeviction");

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final String KEY = RateLimitEpochFence.KEY;
    private static final String SECRET_V1 = "test-only-epoch-secret-v1-0123456789abcdef";
    private static final String SECRET_V2 = "test-only-epoch-secret-v2-0123456789abcdef";
    private static final UUID ORG = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID USER = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final DefaultRedisScript<Long> ABSOLUTE_EXPIRY = new DefaultRedisScript<>(
            "return redis.call('PEXPIRETIME', KEYS[1])", Long.class);

    private record KeySnapshot(DataType type, Object value, long expiresAtMillis) {}

    @Test
    void mixedHmacVersionsAndMissingOrPausedFenceNeverAllocateCounters() throws IOException {
        var firstFactory = connection();
        var secondFactory = connection();
        try {
            var redis = template(firstFactory);
            var otherRedis = template(secondFactory);
            flush(redis);
            var v1 = keys(SECRET_V1, "v1");
            var v2 = keys(SECRET_V2, "v2");
            var wrongSecret = keys(SECRET_V2, "v1");
            var wrongVersion = keys(SECRET_V1, "v2");
            var wrongPrefix = new RateLimitKeyFactory(new RateLimitRedisKeyProperties("different", SECRET_V1, "v1"));
            var first = guarded(redis, v1);
            var second = guarded(otherRedis, v1);
            assertEpochDenied(() -> login(first, v1));
            assertThat(redis.keys("*")).isEmpty();
            redis.opsForValue().set("existing-counter", "1", WINDOW);
            assertThatThrownBy(() -> admin(redis, "initialize-empty", v1, v1))
                    .hasStackTraceContaining("EPOCH_INITIALIZATION_DENIED");
            assertThat(redis.opsForValue().get("existing-counter")).isEqualTo("1");
            redis.delete("existing-counter"); // test-only fixture
            assertThat(admin(redis, "initialize-empty", v1, v1)).isEqualTo("ACTIVE");
            assertThatThrownBy(() -> admin(redis, "rebind-preserved", v1, v1))
                    .hasStackTraceContaining("EPOCH_REBIND_DENIED");
            assertThat(login(first, v1).allowed()).isTrue();
            assertThat(login(second, v1).allowed()).isFalse();
            assertThat(first.incrementRefreshAndGet(v1.refreshIp("192.0.2.5"), WINDOW).count()).isEqualTo(1);
            assertThat(ai(first, v1).allowed()).isTrue();
            var before = Objects.requireNonNull(redis.keys("*"));
            for (var wrong : List.of(v2, wrongSecret, wrongVersion, wrongPrefix)) {
                var stale = guarded(otherRedis, wrong);
                assertEpochDenied(() -> login(stale, wrong));
                assertEpochDenied(() -> stale.incrementRefreshAndGet(wrong.refreshIp("192.0.2.5"), WINDOW));
                assertEpochDenied(() -> ai(stale, wrong));
                assertEpochDenied(() -> stale.incrementAndGet("new-single", WINDOW));
            }
            assertThat(redis.keys("*")).isEqualTo(before);
            assertThat(redis.opsForValue().get(v1.loginEmail("user@example.invalid"))).isEqualTo("2");
            assertThat(redis.opsForValue().get(v1.refreshIp("192.0.2.5"))).isEqualTo("1");
            assertThat(redis.opsForValue().get(v1.aiMessageOrganization(ORG))).isEqualTo("1");
            assertThatThrownBy(() -> admin(redis, "pause", v2, v2)).hasStackTraceContaining("EPOCH_COMPARE_AND_SET_FAILED");
            assertThat(admin(redis, "pause", v1, v1)).isEqualTo("PAUSED");
            String status = admin(redis, "status", v1, v1);
            assertThat(status).startsWith("PAUSED:");
            assertThat(Long.parseLong(Objects.requireNonNull(status).substring("PAUSED:".length())))
                    .isBetween(86_400_000L, 86_410_000L);
            assertEpochDenied(() -> login(first, v1));
            assertEpochDenied(() -> login(guarded(otherRedis, v2), v2));
            assertThatThrownBy(() -> admin(redis, "activate", v1, v2)).hasStackTraceContaining("EPOCH_DRAIN_NOT_COMPLETE");
            assertThat(redis.keys("*")).isEqualTo(before);

            // Isolated fixture models passage of the full drain. No clock bypass
            // or shortened drain is available in the production script.
            String paused = Objects.requireNonNull(redis.opsForValue().get(KEY));
            redis.opsForValue().set(KEY, paused.substring(0, paused.lastIndexOf(':') + 1) + "1");
            assertThat(admin(redis, "activate", v1, v2)).isEqualTo("ACTIVE");
            assertEpochDenied(() -> login(first, v1));
            assertEpochDenied(() -> login(second, v1));
            assertThat(login(guarded(otherRedis, v2), v2).allowed()).isTrue();
            assertThat(redis.opsForValue().get(v1.loginEmail("user@example.invalid"))).isEqualTo("2");
        } finally {
            secondFactory.destroy();
            firstFactory.destroy();
        }
    }

    @Test
    void durableRestartRejectsAdmissionUntilExplicitRebindAndPreservesAllCounters() throws IOException {
        var keys = keys(SECRET_V1, "v1");
        String refresh = keys.refreshIp("192.0.2.5");
        Map<String, KeySnapshot> before;
        String originalFence;
        long originalTtl;

        var factory = connection();
        try {
            var redis = template(factory);
            flush(redis);
            var limiter = guarded(redis, keys);
            assertThat(admin(redis, "initialize-empty", keys, keys)).isEqualTo("ACTIVE");
            assertThat(login(limiter, keys).allowed()).isTrue();
            assertThat(ai(limiter, keys).allowed()).isTrue();
            assertThat(limiter.incrementRefreshAndGet(refresh, WINDOW).count()).isEqualTo(1);
            before = snapshot(redis);
            assertThat(before).containsKey(KEY);
            assertThat(before.values()).anyMatch(entry -> entry.type() == DataType.ZSET);
            originalFence = Objects.requireNonNull(redis.opsForValue().get(KEY));
            originalTtl = Objects.requireNonNull(redis.getExpire(refresh));
            assertThat(originalTtl).isPositive();

            // Borrowed Docker client; Testcontainers owns it.
            @SuppressWarnings("resource")
            var docker = DockerClientFactory.instance().client();
            String containerId = redisContainer.getContainerId();
            docker.killContainerCmd(containerId).withSignal("SIGKILL").exec();
            try {
                await().alias("Redis actually stopped after SIGKILL")
                        .atMost(Duration.ofSeconds(10)).until(() ->
                                Boolean.FALSE.equals(redisContainer.getCurrentContainerInfo().getState().getRunning()));
                assertThatThrownBy(() -> login(limiter, keys)).isInstanceOf(DataAccessException.class);
            } finally {
                // Restart the SAME container and data volume, never create a replacement.
                docker.startContainerCmd(containerId).exec();
            }
        } finally {
            // The pre-crash Lettuce connection must not be reused for recovery checks.
            factory.destroy();
        }

        var recoveredFactory = connection();
        try {
            var redis = template(recoveredFactory);
            awaitRedisReady(recoveredFactory);
            var limiter = guarded(redis, keys);
            // Readiness is not proof of durability. Check the original fence and
            // every persisted value/score/absolute TTL immediately, without retries.
            assertThat(redis.opsForValue().get(KEY)).isEqualTo(originalFence);
            assertThat(snapshot(redis)).isEqualTo(before);
            assertThat(admin(redis, "status", keys, keys)).isEqualTo("PRIMARY_CHANGED");
            assertEpochDenied(() -> login(limiter, keys));
            assertEpochDenied(() -> ai(limiter, keys));
            assertEpochDenied(() -> limiter.incrementRefreshAndGet(refresh, WINDOW));
            assertEpochDenied(() -> limiter.incrementAndGet("new-single", WINDOW));
            assertThat(snapshot(redis)).isEqualTo(before);
            assertThat(redis.opsForValue().get(keys.loginEmail("user@example.invalid"))).isEqualTo("1");
            assertThat(redis.opsForValue().get(keys.loginIp("192.0.2.5"))).isEqualTo("1");
            assertThat(redis.opsForValue().get(keys.aiMessageUser(ORG, USER))).isEqualTo("1");
            assertThat(redis.opsForValue().get(keys.aiMessageOrganization(ORG))).isEqualTo("1");
            assertThat(redis.opsForValue().get(refresh)).isEqualTo("1");
            assertThat(redis.getExpire(refresh)).isPositive().isLessThanOrEqualTo(originalTtl);
            assertThat(admin(redis, "rebind-preserved", keys, keys)).isEqualTo("ACTIVE");
            assertThat(login(limiter, keys).allowed()).isFalse();
            assertThat(ai(limiter, keys).allowed()).isFalse();
            assertThat(limiter.incrementRefreshAndGet(refresh, WINDOW).count()).isEqualTo(2);
        } finally {
            recoveredFactory.destroy();
        }
    }

    @Test
    void corruptOrExpiringFenceCannotBeRecreatedByApplication() throws IOException {
        var factory = connection();
        try {
            var redis = template(factory);
            flush(redis);
            var keys = keys(SECRET_V1, "v1");
            var limiter = guarded(redis, keys);
            assertThat(admin(redis, "initialize-empty", keys, keys)).isEqualTo("ACTIVE");
            redis.expire(KEY, WINDOW);
            assertEpochDenied(() -> login(limiter, keys));
            redis.opsForValue().set(KEY, "corrupt");
            assertEpochDenied(() -> login(limiter, keys));
            assertThat(redis.opsForValue().get(KEY)).isEqualTo("corrupt");
            assertThat(redis.keys("*")).containsExactly(KEY);
            redis.delete(KEY);
            assertEpochDenied(() -> login(limiter, keys));
            assertThat(redis.keys("*")).isEmpty();
        } finally {
            factory.destroy();
        }
    }

    private void awaitRedisReady(LettuceConnectionFactory factory) {
        try {
            await().alias("Redis PING after restart and AOF replay")
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(250))
                    .ignoreExceptionsInstanceOf(DataAccessException.class)
                    .until(() -> {
                        assertThat(redisContainer.getCurrentContainerInfo().getState().getRunning())
                                .as("Redis process is running")
                                .isTrue();
                        try (var connection = factory.getConnection()) {
                            return "PONG".equals(connection.ping());
                        }
                    });
        } catch (ConditionTimeoutException | AssertionError failure) {
            throw new AssertionError("Redis did not complete startup/AOF replay:\n" + redisContainer.getLogs(), failure);
        }
    }

    private static Map<String, KeySnapshot> snapshot(StringRedisTemplate redis) {
        Map<String, KeySnapshot> result = new HashMap<>();
        for (String key : Objects.requireNonNull(redis.keys("*"))) {
            DataType type = Objects.requireNonNull(redis.type(key));
            Object value = switch (type) {
                case STRING -> Objects.requireNonNull(redis.opsForValue().get(key));
                case ZSET -> Objects.requireNonNull(redis.opsForZSet().rangeWithScores(key, 0, -1))
                        .stream().collect(Collectors.toUnmodifiableMap(
                                tuple -> Objects.requireNonNull(tuple.getValue()),
                                tuple -> Objects.requireNonNull(tuple.getScore())));
                default -> throw new AssertionError("Unexpected RateLimit key type: " + type);
            };
            long expiry = Objects.requireNonNull(redis.execute(ABSOLUTE_EXPIRY, List.of(key)));
            if (expiry != -1L) {
                assertThat(expiry).isPositive();
                assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive();
            }
            result.put(key, new KeySnapshot(type, value, expiry));
        }
        return Map.copyOf(result);
    }

    private LettuceConnectionFactory connection() {
        // Docker Desktop can publish a different host port after a restart.
        // Read a fresh inspect response, not the cached original mapping.
        var bindings = redisContainer.getCurrentContainerInfo().getNetworkSettings().getPorts()
                .getBindings().get(ExposedPort.tcp(6379));
        assertThat(bindings).as("Current Redis host port mapping").isNotEmpty();
        int port = Integer.parseInt(bindings[0].getHostPortSpec());
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                redisContainer.getHost(), port), LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3)).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static StringRedisTemplate template(LettuceConnectionFactory factory) {
        var redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        return redis;
    }

    private static void flush(StringRedisTemplate redis) {
        try (var connection = Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    private static RateLimitKeyFactory keys(String secret, String version) {
        return new RateLimitKeyFactory(new RateLimitRedisKeyProperties("epoch-test", secret, version));
    }

    private static RedisFixedWindowRateLimiter guarded(StringRedisTemplate redis, RateLimitKeyFactory keys) {
        var environment = new MockEnvironment().withProperty("safeai.rate-limit.redis.epoch-fence-enabled", "false");
        environment.setActiveProfiles("prod"); // production cannot disable this fence
        return new RedisFixedWindowRateLimiter(redis, keys, environment);
    }

    private static String admin(StringRedisTemplate redis, String action, RateLimitKeyFactory old,
                                RateLimitKeyFactory next) throws IOException {
        String lua = new ClassPathResource("ratelimit/epoch-admin.lua").getContentAsString(StandardCharsets.UTF_8);
        return redis.execute(new DefaultRedisScript<>(lua, String.class), List.of(KEY), action,
                old.epochFingerprint(), next.epochFingerprint());
    }

    private static void assertEpochDenied(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("RATE_LIMIT_EPOCH_UNAVAILABLE");
    }

    private static DualRateLimitResult login(RedisFixedWindowRateLimiter limiter, RateLimitKeyFactory keys) {
        String email = keys.loginEmail("user@example.invalid");
        String ip = keys.loginIp("192.0.2.5");
        return limiter.incrementBothAndCheck(email, keys.exceededMarker(email), 1, ip, keys.exceededMarker(ip), 1, WINDOW);
    }

    private static DualRateLimitResult ai(RedisFixedWindowRateLimiter limiter, RateLimitKeyFactory keys) {
        String user = keys.aiMessageUser(ORG, USER);
        String org = keys.aiMessageOrganization(ORG);
        return limiter.tryIncrementBoth(user, keys.exceededMarker(user), 1, org, keys.exceededMarker(org), 1, WINDOW);
    }
}
