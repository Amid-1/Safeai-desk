package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisFixedWindowRateLimiterIT {

    private static final int REDIS_PORT = 6379;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Container
    @SuppressWarnings("resource") // Lifecycle is managed by the JUnit Testcontainers extension.
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(REDIS_PORT);

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisFixedWindowRateLimiter limiter;

    @BeforeEach
    void init() {
        var config = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        limiter = new RedisFixedWindowRateLimiter(redis);
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @AfterEach
    void close() {
        if (factory != null) factory.destroy();
    }

    @Test
    void dualReservationIsAtomicUnderConcurrency() throws Exception {
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> futures = executor.invokeAll(createConcurrentTasks(80));
            int admitted = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) admitted++;
            }
            assertThat(admitted).isEqualTo(25);
            assertThat(redis.opsForValue().get("{test}:user")).isEqualTo("25");
            assertThat(redis.opsForValue().get("{test}:org")).isEqualTo("25");
        }
    }

    @Test
    void repeatedIncrementDoesNotExtendOriginalWindow() throws Exception {
        Duration ttl = Duration.ofMillis(1800);
        limiter.incrementAndGet("ttl-key", ttl);
        Long first = redis.getExpire("ttl-key", TimeUnit.MILLISECONDS);
        Thread.sleep(250);
        limiter.incrementAndGet("ttl-key", ttl);
        Long second = redis.getExpire("ttl-key", TimeUnit.MILLISECONDS);
        assertThat(first).isNotNull().isPositive();
        assertThat(second).isNotNull().isPositive().isLessThan(first);
    }

    @Test
    void malformedSecondCounterFailsBeforeAnyWrites() {
        redis.opsForValue().set("{fault}:org", "not-a-number");
        assertThatThrownBy(() -> limiter.incrementBothAndCheck(
                "{fault}:user", "{fault}:user:exceeded", 10,
                "{fault}:org", "{fault}:org:exceeded", 10, WINDOW))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.opsForValue().get("{fault}:user")).isNull();
        assertThat(redis.opsForValue().get("{fault}:org")).isEqualTo("not-a-number");
        assertThat(redis.hasKey("{fault}:user:exceeded")).isFalse();
        assertThat(redis.hasKey("{fault}:org:exceeded")).isFalse();
    }

    @Test
    void malformedFirstCounterFailsBeforeAnyWrites() {
        redis.opsForValue().set("{fault}:user", "not-a-number");
        assertThatThrownBy(() -> limiter.incrementBothAndCheck(
                "{fault}:user", "{fault}:user:exceeded", 10,
                "{fault}:org", "{fault}:org:exceeded", 10, WINDOW))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.opsForValue().get("{fault}:user")).isEqualTo("not-a-number");
        assertThat(redis.opsForValue().get("{fault}:org")).isNull();
    }

    @Test
    void malformedSecondCounterAlsoFailsBeforeTryReservationWrites() {
        redis.opsForValue().set("{fault}:org", "not-a-number");
        assertThatThrownBy(() -> limiter.tryIncrementBoth(
                "{fault}:user", "{fault}:user:exceeded", 10,
                "{fault}:org", "{fault}:org:exceeded", 10, WINDOW))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.opsForValue().get("{fault}:user")).isNull();
        assertThat(redis.opsForValue().get("{fault}:org")).isEqualTo("not-a-number");
    }

    @Test
    void malformedIpCounterDoesNotDeleteLoginEmailCounter() {
        redis.opsForValue().set("{login}:email", "2");
        redis.opsForValue().set("{login}:ip", "not-a-number");
        assertThatThrownBy(() -> limiter.resetFirstAndDecrementSecond(
                "{login}:email", "{login}:ip",
                "{login}:email:exceeded", "{login}:ip:exceeded"))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.opsForValue().get("{login}:email")).isEqualTo("2");
        assertThat(redis.opsForValue().get("{login}:ip")).isEqualTo("not-a-number");
    }

    @Test
    void oversizedCounterIsRejectedWithoutPartialIncrement() {
        redis.opsForValue().set("{overflow}:org", "9223372036854775807");
        assertThatThrownBy(() -> limiter.incrementBothAndCheck(
                "{overflow}:user", "{overflow}:user:exceeded", 10,
                "{overflow}:org", "{overflow}:org:exceeded", 10, WINDOW))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.opsForValue().get("{overflow}:user")).isNull();
        assertThat(redis.opsForValue().get("{overflow}:org"))
                .isEqualTo("9223372036854775807");
    }

    private List<Callable<Boolean>> createConcurrentTasks(int totalRequests) {
        List<Callable<Boolean>> tasks = new ArrayList<>(totalRequests);
        for (int i = 0; i < totalRequests; i++) {
            tasks.add(() -> limiter.tryIncrementBoth(
                    "{test}:user", "{test}:user:exceeded", 25,
                    "{test}:org", "{test}:org:exceeded", 40, WINDOW).allowed());
        }
        return tasks;
    }
}
