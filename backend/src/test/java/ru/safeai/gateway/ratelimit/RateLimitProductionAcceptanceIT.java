package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.exception.RateLimitExceededException;
import ru.safeai.gateway.ratelimit.exception.RateLimitUnavailableException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-instance service acceptance, not a provider RPM/TPM or HTTP load test.
 * Each test owns a disposable Redis; never accepts an external endpoint.
 */
@Testcontainers
@Timeout(120)
class RateLimitProductionAcceptanceIT {
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final String EMAIL = "user@example.invalid";
    private static final String IP = "192.0.2.42";
    private static final String SECRET = "test-only-acceptance-secret-0123456789abcdef";
    private static final String REDIS_PASSWORD = "test-only-redis-password";
    private static final UUID ORG = new UUID(0, 100);
    private static final UUID OTHER_ORG = new UUID(0, 200);
    private static final int WORKERS = 16;
    private static final int LOGIN_EMAIL_LIMIT = 20;
    private static final int LOGIN_IP_LIMIT = 1000;
    private static final int REFRESH_IP_LIMIT = 30;

    @Container
    @SuppressWarnings("resource") // JUnit owns the lifecycle.
    final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("safeai.ratelimit.test.redis-image", "redis:7.4-alpine")))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always",
                    "--no-appendfsync-on-rewrite", "no", "--aof-load-truncated", "no",
                    "--requirepass", REDIS_PASSWORD, "--maxmemory", "192mb", "--maxmemory-policy", "noeviction");

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private final List<SimpleMeterRegistry> registries = new ArrayList<>();
    private final ConcurrentLinkedQueue<Object> events = new ConcurrentLinkedQueue<>();
    private final RateLimitKeyFactory keys = keyFactory(SECRET);
    private Instance first;
    private Instance second;

    private record Instance(StringRedisTemplate redis, LoginRateLimitService login,
                            RefreshRateLimitService refresh, RedisRateLimitService ai) {}

    @BeforeEach
    void initialize() throws IOException {
        first = instance(keys, 100, 25);
        second = instance(keys, 100, 25);
        assertThat(first.redis().getConnectionFactory()).isNotSameAs(second.redis().getConnectionFactory());
        String lua = new ClassPathResource("ratelimit/epoch-admin.lua")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(first.redis().execute(new DefaultRedisScript<>(lua, String.class),
                List.of(RateLimitEpochFence.KEY), "initialize-empty",
                keys.epochFingerprint(), keys.epochFingerprint())).isEqualTo("ACTIVE");
    }

    @AfterEach
    void closeClients() {
        try {
            factories.forEach(LettuceConnectionFactory::destroy);
        } finally {
            registries.forEach(SimpleMeterRegistry::close);
        }
    }

    @Test
    void concurrentLoginUsesOneNormalizedIdentityAndEmitsOneAuditMarker() throws Exception {
        var accepted = new ConcurrentLinkedQueue<LoginRateLimitService.Attempt>();
        var blocked = new ConcurrentLinkedQueue<Long>();
        race(128, index -> {
            try {
                accepted.add(node(index).login().checkAllowed(
                        index % 2 == 0 ? EMAIL : " USER@EXAMPLE.INVALID ", IP));
            } catch (RateLimitExceededException denial) {
                blocked.add(denial.getRetryAfterSeconds());
            }
        });
        assertThat(accepted).hasSize(20);
        assertThat(accepted.stream().map(LoginRateLimitService.Attempt::id).distinct().count()).isEqualTo(20);
        assertThat(blocked).hasSize(108).allSatisfy(ttl -> assertThat(ttl).isBetween(1L, 600L));
        assertThat(count(keys.loginEmail(EMAIL))).isEqualTo(128);
        assertThat(count(keys.loginIp(IP))).isEqualTo(128);
        assertThat(events).hasSize(1).allSatisfy(event -> {
            assertThat(event).isInstanceOf(LoginRateLimitExceededEvent.class);
            var loginEvent = (LoginRateLimitExceededEvent) event;
            assertThat(loginEvent.emailFingerprint()).isEqualTo(keys.emailFingerprint(EMAIL));
            assertThat(loginEvent.ipFingerprint()).isEqualTo(keys.ipFingerprint(IP));
            assertThat(loginEvent.auditDetails().toString()).doesNotContain(EMAIL, IP, SECRET);
        });
        assertMetrics("login", 20, 108, 0);
        assertThat(redisKeys().toString()).doesNotContain(EMAIL, IP, SECRET);
    }

    @Test
    void concurrentDuplicateCompensationCannotErasePreviousNatFailures() throws Exception {
        String failedEmail = "failed@example.invalid";
        first.login().checkAllowed(failedEmail, IP);
        var receipts = new ConcurrentLinkedQueue<LoginRateLimitService.Attempt>();
        race(64, index -> receipts.add(node(index).login().checkAllowed(
                "success-" + index + "@example.invalid", IP)));
        assertThat(receipts).hasSize(64);
        var attempts = List.copyOf(receipts);
        assertThat(count(keys.loginIp(IP))).isEqualTo(65);
        race(128, index -> node(index).login().onLoginSuccess(attempts.get(index / 2)));
        assertThat(count(keys.loginIp(IP))).isEqualTo(1);
        assertThat(count(keys.loginEmail(failedEmail))).isEqualTo(1);
        for (var attempt : attempts) {
            assertThat(first.redis().opsForValue().get(attempt.emailKey())).isEqualTo("0");
        }
        assertMetrics("login", 65, 0, 0);
        assertThat(registries.stream().mapToLong(registry -> registry.get("safeai.rate.limit.redis.duration")
                .tags("type", "login", "operation", "login_success_cleanup", "outcome", "success")
                .timer().count()).sum()).isEqualTo(128);
    }

    @Test
    void concurrentRefreshCountsEveryAttemptWithoutExtendingFirstWindow() throws Exception {
        first.refresh().checkAllowed(IP);
        String key = keys.refreshIp(IP);
        long expiry = expiration(key);
        var blocked = new ConcurrentLinkedQueue<Long>();
        var accepted = new ConcurrentLinkedQueue<Integer>();
        race(127, index -> {
            try {
                node(index).refresh().checkAllowed(" " + IP + " ");
                accepted.add(index);
            } catch (RateLimitExceededException denial) {
                blocked.add(denial.getRetryAfterSeconds());
            }
        });
        assertThat(accepted).hasSize(29);
        assertThat(blocked).hasSize(98).allSatisfy(ttl -> assertThat(ttl).isBetween(1L, 600L));
        assertThat(count(key)).isEqualTo(128);
        assertThat(expiration(key)).isEqualTo(expiry);
        assertThat(first.redis().opsForZSet().zCard(RedisFixedWindowRateLimiter.REFRESH_ALLOCATION_KEY))
                .isEqualTo(128L);
        assertThat(first.redis().hasKey(keys.loginIp(IP))).isFalse();
        assertMetrics("refresh", 30, 98, 0);
    }

    @Test
    void concurrentAiOrganizationQuotaIsExactAndOtherTenantIsIndependent() throws Exception {
        var accepted = new ConcurrentLinkedQueue<Integer>();
        var rejected = new ConcurrentLinkedQueue<Long>();
        race(128, index -> {
            try {
                node(index).ai().checkAiMessageAllowed(principal(ORG, index % 4));
                accepted.add(index);
            } catch (RateLimitExceededException denial) {
                rejected.add(denial.getRetryAfterSeconds());
            }
        });
        assertThat(accepted).hasSize(25);
        assertThat(rejected).hasSize(103).allSatisfy(ttl -> assertThat(ttl).isBetween(1L, 3600L));
        assertThat(count(keys.aiMessageOrganization(ORG))).isEqualTo(25);
        for (int i = 0; i < 4; i++) {
            final int user = i;
            assertThat(count(keys.aiMessageUser(ORG, userId(i))))
                    .isEqualTo(accepted.stream().filter(index -> index % 4 == user).count());
        }
        assertThat(events).hasSize(1).allSatisfy(event -> {
            assertThat(event).isInstanceOf(RateLimitExceededEvent.class);
            var aiEvent = (RateLimitExceededEvent) event;
            assertThat(aiEvent.targetOrganizationId()).isEqualTo(ORG);
            assertThat(aiEvent.actorEmail()).isNull();
        });
        second.ai().checkAiMessageAllowed(principal(OTHER_ORG, 0));
        assertThat(count(keys.aiMessageOrganization(OTHER_ORG))).isEqualTo(1);
        assertThat(count(keys.aiMessageOrganization(ORG))).isEqualTo(25);
        assertMetrics("ai_message", 26, 103, 0);
    }

    @Test
    void userQuotaDoesNotDebitOrganizationAfterRejection() throws Exception {
        var a = instance(keys, 7, 1000);
        var b = instance(keys, 7, 1000);
        var admitted = new ConcurrentLinkedQueue<Integer>();
        var denied = new ConcurrentLinkedQueue<Long>();
        race(128, index -> {
            try {
                (index % 2 == 0 ? a : b).ai().checkAiMessageAllowed(principal(ORG, 0));
                admitted.add(index);
            } catch (RateLimitExceededException denial) {
                denied.add(denial.getRetryAfterSeconds());
            }
        });
        assertThat(admitted).hasSize(7);
        assertThat(denied).hasSize(121).allSatisfy(ttl -> assertThat(ttl).isPositive());
        assertThat(count(keys.aiMessageUser(ORG, userId(0)))).isEqualTo(7);
        assertThat(count(keys.aiMessageOrganization(ORG))).isEqualTo(7);
        assertMetrics("ai_message", 7, 121, 0);
    }

    @Test
    void globalCapacityRejectsBeforeNewAnonymousIdentityAllocation() throws Exception {
        String loginGuard = RedisFixedWindowRateLimiter.loginAllocationKey(keys.loginEmail(EMAIL));
        String refreshGuard = RedisFixedWindowRateLimiter.REFRESH_ALLOCATION_KEY;
        fillGuard(loginGuard, RedisFixedWindowRateLimiter.LOGIN_ALLOCATION_LIMIT);
        fillGuard(refreshGuard, RedisFixedWindowRateLimiter.REFRESH_ALLOCATION_LIMIT);
        var before = redisKeys();
        race(32, index -> {
            assertThatThrownBy(() -> node(index).login().checkAllowed("fresh-" + index + "@example.invalid", IP))
                    .isInstanceOfSatisfying(RateLimitExceededException.class,
                            denial -> assertThat(denial.getRetryAfterSeconds()).isPositive());
            assertThatThrownBy(() -> node(index).refresh().checkAllowed("198.51.100." + (index + 1)))
                    .isInstanceOfSatisfying(RateLimitExceededException.class,
                            denial -> assertThat(denial.getRetryAfterSeconds()).isPositive());
        });
        assertThat(redisKeys()).isEqualTo(before);
        assertThat(first.redis().opsForZSet().zCard(loginGuard))
                .isEqualTo(RedisFixedWindowRateLimiter.LOGIN_ALLOCATION_LIMIT);
        assertThat(first.redis().opsForZSet().zCard(refreshGuard))
                .isEqualTo(RedisFixedWindowRateLimiter.REFRESH_ALLOCATION_LIMIT);
        for (String type : List.of("login", "refresh")) {
            assertMetrics(type, 0, 32, 0);
            assertThat(registries.stream().mapToDouble(registry -> registry.get("safeai.rate.limit.rejected")
                    .tags("type", type, "dimension", "capacity").counter().count()).sum()).isEqualTo(32.0);
        }
    }

    @Test
    void missingFenceAndWrongHmacFailClosedWithoutAllocatingAnyKeys() throws Exception {
        var wrong = instance(keyFactory(SECRET + "-stale"), 100, 25);
        var before = redisKeys();
        assertUnavailable(wrong);
        assertThat(redisKeys()).isEqualTo(before);
        first.redis().delete(RateLimitEpochFence.KEY); // disposable fixture only
        assertThat(redisKeys()).isEmpty();
        race(32, index -> assertUnavailable(node(index)));
        assertThat(redisKeys()).isEmpty();
        for (String type : List.of("login", "refresh", "ai_message")) {
            assertMetrics(type, 0, 0, 33);
        }
        assertThat(events).isEmpty();
    }

    @Test
    void redisProcessStoppedMakesAllServicesFailClosed() {
        container.stop(); // Only this JUnit-owned Redis, never a developer or production instance.
        assertUnavailable(first);
        assertUnavailable(second);
        for (String type : List.of("login", "refresh", "ai_message")) {
            assertMetrics(type, 0, 0, 2);
        }
        assertThat(events).isEmpty();
    }

    private static void assertUnavailable(Instance instance) {
        assertThatThrownBy(() -> instance.login().checkAllowed(EMAIL, IP))
                .isInstanceOf(RateLimitUnavailableException.class);
        assertThatThrownBy(() -> instance.refresh().checkAllowed(IP))
                .isInstanceOf(RateLimitUnavailableException.class);
        assertThatThrownBy(() -> instance.ai().checkAiMessageAllowed(principal(ORG, 0)))
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    private Instance instance(RateLimitKeyFactory keyFactory, int userLimit, int organizationLimit) {
        var configuration = new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379));
        configuration.setPassword(RedisPassword.of(REDIS_PASSWORD));
        var factory = new LettuceConnectionFactory(configuration,
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).build());
        factories.add(factory);
        factory.afterPropertiesSet();
        factory.start();
        var redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        var environment = new MockEnvironment().withProperty("safeai.rate-limit.redis.epoch-fence-enabled", "false");
        environment.setActiveProfiles("prod");
        var limiter = new RedisFixedWindowRateLimiter(redis, keyFactory, environment);
        var registry = new SimpleMeterRegistry();
        registries.add(registry);
        var metrics = new RateLimitMetrics(registry);
        ApplicationEventPublisher publisher = events::add;
        return new Instance(redis,
                new LoginRateLimitService(limiter, new LoginRateLimitProperties(
                        true, LOGIN_EMAIL_LIMIT, LOGIN_IP_LIMIT, WINDOW, ORG),
                        keyFactory, publisher, metrics),
                new RefreshRateLimitService(limiter, new RefreshRateLimitProperties(true, REFRESH_IP_LIMIT, WINDOW),
                        keyFactory, metrics),
                new RedisRateLimitService(limiter, new AiMessageRateLimitProperties(true, userLimit, userLimit,
                        organizationLimit), publisher, keyFactory, metrics));
    }

    private static RateLimitKeyFactory keyFactory(String secret) {
        return new RateLimitKeyFactory(new RateLimitRedisKeyProperties("acceptance-test", secret, "v1"));
    }

    private static UUID userId(int id) {
        return new UUID(1, id);
    }

    private static SafeAiUserPrincipal principal(UUID org, int user) {
        return SafeAiUserPrincipal.accessTokenPrincipal(userId(user), org, 0L, 0L,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private Instance node(int index) {
        return index % 2 == 0 ? first : second;
    }

    private long count(String key) {
        String value = first.redis().opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private long expiration(String key) {
        long timestamp = Objects.requireNonNull(first.redis().execute(new DefaultRedisScript<>(
                "return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key)));
        assertThat(timestamp).isPositive();
        return timestamp;
    }

    private void fillGuard(String key, int size) {
        // Seed only the allocation ledger in the disposable Redis. The existing
        // distributedAttackCannotExceedSharedAllocationCapacityAcrossInstances
        // scenario separately exercises real reservation creation to the limit.
        var seed = new DefaultRedisScript<>("""
                local t = redis.call('TIME')
                local expiry = tonumber(t[1]) * 1000 + 600000
                for i = 1, tonumber(ARGV[1]) do
                    redis.call('ZADD', KEYS[1], expiry, 'fixture-' .. i)
                end
                return redis.call('ZCARD', KEYS[1])
                """, Long.class);
        assertThat(first.redis().execute(seed, List.of(key), Integer.toString(size))).isEqualTo(size);
    }

    private Set<String> redisKeys() {
        return Set.copyOf(Objects.requireNonNull(first.redis().keys("*")));
    }

    private void assertMetrics(String type, long allowed, long rejected, long unavailable) {
        assertThat(registries.stream().mapToDouble(registry -> registry.get("safeai.rate.limit.allowed")
                .tag("type", type).counter().count()).sum()).isEqualTo(allowed);
        assertThat(registries.stream().flatMap(registry -> registry.find("safeai.rate.limit.rejected")
                .tag("type", type).counters().stream()).mapToDouble(Counter::count).sum())
                .isEqualTo(rejected);
        assertThat(registries.stream().mapToDouble(registry -> registry.get("safeai.rate.limit.unavailable")
                .tag("type", type).counter().count()).sum()).isEqualTo(unavailable);
    }

    private static void race(int operations, IntConsumer action) throws Exception {
        var ready = new CountDownLatch(WORKERS);
        var start = new CountDownLatch(1);
        var durations = new long[operations];
        List<Callable<Void>> workers = IntStream.range(0, WORKERS).<Callable<Void>>mapToObj(worker -> () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent start timed out");
            }
            for (int i = worker; i < operations; i += WORKERS) {
                long begin = System.nanoTime();
                action.accept(i);
                durations[i] = System.nanoTime() - begin;
            }
            return null;
        }).toList();
        try (var pool = Executors.newFixedThreadPool(WORKERS)) {
            var futures = workers.stream().map(pool::submit).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                long begin = System.nanoTime();
                start.countDown();
                long deadline = begin + TimeUnit.SECONDS.toNanos(75);
                for (var future : futures) {
                    future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
                double elapsed = (System.nanoTime() - begin) / 1_000_000_000.0;
                Arrays.sort(durations);
                // Diagnostic evidence, not a production latency SLA. Some scenarios
                // deliberately contain multiple operations or an unavailable Redis.
                System.out.printf(Locale.ROOT,
                        "RateLimit workload: workers=%d iterations=%d elapsed=%.3fs p95=%.3fms p99=%.3fms%n",
                        WORKERS, operations, elapsed,
                        durations[(int) Math.ceil(0.95 * operations) - 1] / 1_000_000.0,
                        durations[(int) Math.ceil(0.99 * operations) - 1] / 1_000_000.0);
            } finally {
                start.countDown();
                futures.forEach(future -> future.cancel(true));
                pool.shutdownNow();
            }
        }
    }
}
