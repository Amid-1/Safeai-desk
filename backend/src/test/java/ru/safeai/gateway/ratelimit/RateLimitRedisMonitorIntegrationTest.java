package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("integration")
@Timeout(60)
class RateLimitRedisMonitorIntegrationTest {
    private static final String LOGIN = "safeai:rate-limit:guard:{login}:allocations";
    private static final String REFRESH = RedisFixedWindowRateLimiter.REFRESH_ALLOCATION_KEY;
    private static final String EPOCH = RateLimitEpochFence.KEY;
    private static final String PREFIX = "safeai.rate.limit.redis.";

    @Test
    void observesRealRedisWithoutWritingAndReportsFenceTransitionsAndRecovery() throws IOException, InterruptedException {
        try (var redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))) {
            redisContainer.withExposedPorts(6379)
                    .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always",
                            "--maxmemory", "192mb", "--maxmemory-policy", "noeviction");
            redisContainer.start();
            var configuration = new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379));
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build();
            var factory = new LettuceConnectionFactory(configuration, client);
            try {
                factory.afterPropertiesSet();
                factory.start();
                var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
                try {
                    verifyObservation(redisContainer, factory, registry);
                } finally {
                    registry.close();
                }
            } finally {
                factory.destroy();
            }
        }
    }

    private static void verifyObservation(GenericContainer<?> redisContainer, LettuceConnectionFactory factory,
                                      PrometheusMeterRegistry registry) throws IOException, InterruptedException {
        var redis = new StringRedisTemplate(factory);
        var keys = new RateLimitKeyFactory(new RateLimitRedisKeyProperties("monitor", "test-only-monitor-secret-0123456789abcdef", "v1"));
        var env = new MockEnvironment().withProperty("safeai.rate-limit.redis.epoch-fence-enabled", "true");
        var clock = mock(Clock.class);
        var initialTime = Instant.parse("2026-11-22T12:00:00Z");
        when(clock.instant()).thenReturn(initialTime);
        var monitor = new RateLimitRedisMonitor(redis, keys, env, registry, clock);
        assertThat(value(registry, "observation.healthy")).isZero();
        assertThat(value(registry, "memory.max.bytes")).isNaN();
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(LOGIN, "expired", now - 600_000);
        redis.opsForZSet().add(LOGIN, "active", now + 600_000);
        redis.opsForZSet().add(REFRESH, "active", now + 600_000);
        monitor.observe();
        assertThat(value(registry, "observation.healthy")).isEqualTo(1);
        assertState(registry, "missing");
        assertThat(value(registry, "memory.max.bytes")).isEqualTo(192 * 1024 * 1024);
        assertThat(value(registry, "memory.accounted.bytes")).isPositive();
        assertThat(value(registry, "noeviction")).isEqualTo(1);
        assertThat(value(registry, "aof.healthy")).isEqualTo(1);
        assertThat(value(registry, "primary")).isEqualTo(1);
        assertThat(value(registry, "evicted.keys")).isZero();
        assertThat(registry.scrape()).contains("safeai_rate_limit_redis_observation_healthy",
                "safeai_rate_limit_redis_last_observation_timestamp", "safeai_rate_limit_redis_epoch_required",
                "safeai_rate_limit_redis_epoch_state", "safeai_rate_limit_redis_memory_max_bytes",
                "safeai_rate_limit_redis_memory_accounted_bytes", "safeai_rate_limit_redis_memory_rss_bytes",
                "safeai_rate_limit_redis_noeviction", "safeai_rate_limit_redis_primary",
                "safeai_rate_limit_redis_aof_healthy", "safeai_rate_limit_redis_evicted_keys",
                "safeai_rate_limit_redis_allocation_active", "safeai_rate_limit_redis_allocation_limit");
        assertThat(registry.get(PREFIX + "allocation.active").tag("type", "login").gauge().value()).isEqualTo(1);
        assertThat(registry.get(PREFIX + "allocation.active").tag("type", "refresh").gauge().value()).isEqualTo(1);
        assertThat(redis.opsForZSet().range(LOGIN, 0, -1)).containsExactly("expired", "active");
        assertThat(redis.hasKey(EPOCH)).isFalse();
        String run;
        String repl;
        try (var connection = factory.getConnection()) {
            run = Objects.requireNonNull(connection.serverCommands().info("server")).getProperty("run_id");
            repl = Objects.requireNonNull(connection.serverCommands().info("replication")).getProperty("master_replid");
        }
        String fp = keys.epochFingerprint();
        String active = "ACTIVE:" + fp + ':' + run + ':' + repl;
        redis.opsForValue().set(EPOCH, active);
        monitor.observe();
        assertState(registry, "active");
        assertThat(redis.opsForValue().get(EPOCH)).isEqualTo(active);
        // Read-only observation must still succeed when Redis is over its memory cap.
        assertThat(redisContainer.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "1").getStdout()).contains("OK");
        try {
            monitor.observe();
            assertThat(value(registry, "observation.healthy")).isEqualTo(1);
            assertThat(value(registry, "memory.max.bytes")).isEqualTo(1);
            assertThat(value(registry, "memory.accounted.bytes")).isGreaterThan(1);
            assertThat(redis.opsForValue().get(EPOCH)).isEqualTo(active);
            assertThat(redis.opsForZSet().range(LOGIN, 0, -1)).containsExactly("expired", "active");
        } finally {
            assertThat(redisContainer.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "192mb")
                    .getStdout()).contains("OK");
        }
        redis.opsForValue().set(EPOCH, "ACTIVE:" + "0".repeat(64) + ':' + run + ':' + repl);
        monitor.observe();
        assertState(registry, "mismatch");
        redis.opsForValue().set(EPOCH, "ACTIVE:" + fp + ':' + "0".repeat(40) + ':' + repl);
        monitor.observe();
        assertState(registry, "primary_changed");
        redis.opsForValue().set(EPOCH, "PAUSED:" + fp + ':' + run + ':' + repl + ':' + (now + 600_000));
        monitor.observe();
        assertState(registry, "paused");
        redis.opsForValue().set(EPOCH, active, Duration.ofMinutes(5));
        monitor.observe();
        assertState(registry, "invalid");
        double lastSuccess = value(registry, "last.observation.timestamp");
        assertThat(lastSuccess).isEqualTo(initialTime.getEpochSecond());
        when(clock.instant()).thenReturn(initialTime.plusSeconds(60));
        redis.delete(LOGIN);
        redis.opsForValue().set(LOGIN, "wrong-type");
        monitor.observe();
        assertState(registry, "unavailable");
        assertThat(value(registry, "observation.healthy")).isZero();
        assertThat(value(registry, "memory.max.bytes")).isNaN();
        assertThat(value(registry, "last.observation.timestamp")).isEqualTo(lastSuccess);
        assertThat(redis.opsForValue().get(LOGIN)).isEqualTo("wrong-type");
        redis.delete(LOGIN);
        redis.opsForValue().set(EPOCH, active);
        monitor.observe();
        assertState(registry, "active");
        assertThat(value(registry, "observation.healthy")).isEqualTo(1);
        assertThat(value(registry, "last.observation.timestamp")).isEqualTo(initialTime.plusSeconds(60).getEpochSecond());
        assertThat(registry.get(PREFIX + "allocation.active").tag("type", "login").gauge().value()).isZero();
    }

    private static double value(MeterRegistry registry, String suffix) {
        return registry.get(PREFIX + suffix).gauge().value();
    }

    private static void assertState(MeterRegistry registry, String expected) {
        assertThat(registry.find(PREFIX + "epoch.state").gauges()).hasSize(8).allSatisfy(gauge ->
                assertThat(gauge.value()).isEqualTo(expected.equals(gauge.getId().getTag("state")) ? 1 : 0));
    }
}
