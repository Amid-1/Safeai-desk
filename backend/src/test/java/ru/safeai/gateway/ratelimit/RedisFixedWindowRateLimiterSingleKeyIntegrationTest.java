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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisFixedWindowRateLimiterSingleKeyIntegrationTest {

    private static final int REDIS_PORT =
            6379;

    private static final String KEY =
            "safeai:test:v1:rate-limit:refresh:ip:test";

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse(
                            "redis:7.4-alpine"
                    )
            ).withExposedPorts(
                    REDIS_PORT
            );

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
                        REDIS.getMappedPort(
                                REDIS_PORT
                        )
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
                     connectionFactory
                             .getConnection()) {

            connection
                    .serverCommands()
                    .flushDb();
        }
    }

    @Test
    void incrementAndGetCreatesCounterAndPreservesFixedWindow() {
        Duration window =
                Duration.ofSeconds(10);

        RateLimitResult first =
                rateLimiter.incrementAndGet(
                        KEY,
                        window
                );

        RateLimitResult second =
                rateLimiter.incrementAndGet(
                        KEY,
                        window
                );

        assertThat(first.count())
                .isEqualTo(1L);

        assertThat(second.count())
                .isEqualTo(2L);

        assertThat(first.ttlSeconds())
                .isBetween(
                        1L,
                        10L
                );

        assertThat(second.ttlSeconds())
                .isBetween(
                        1L,
                        first.ttlSeconds()
                );

        assertThat(
                redisTemplate
                        .opsForValue()
                        .get(KEY)
        ).isEqualTo(
                "2"
        );

        Long ttlMillis =
                redisTemplate.getExpire(
                        KEY,
                        TimeUnit.MILLISECONDS
                );

        assertThat(ttlMillis)
                .isNotNull()
                .isPositive()
                .isLessThanOrEqualTo(
                        window.toMillis()
                );
    }

    @Test
    void subsequentIncrementDoesNotResetWindowTtl()
            throws Exception {

        Duration window =
                Duration.ofSeconds(5);

        RateLimitResult first =
                rateLimiter.incrementAndGet(
                        KEY,
                        window
                );

        Long ttlAfterFirst =
                redisTemplate.getExpire(
                        KEY,
                        TimeUnit.MILLISECONDS
                );

        Thread.sleep(250L);

        RateLimitResult second =
                rateLimiter.incrementAndGet(
                        KEY,
                        window
                );

        Long ttlAfterSecond =
                redisTemplate.getExpire(
                        KEY,
                        TimeUnit.MILLISECONDS
                );

        assertThat(first.count())
                .isEqualTo(1L);

        assertThat(second.count())
                .isEqualTo(2L);

        assertThat(ttlAfterFirst)
                .isNotNull()
                .isPositive();

        assertThat(ttlAfterSecond)
                .isNotNull()
                .isPositive()
                .isLessThan(
                        ttlAfterFirst
                );
    }

    @Test
    void concurrentIncrementsLoseNoUpdates()
            throws Exception {

        int attempts = 100;

        Duration window =
                Duration.ofSeconds(30);

        CountDownLatch start =
                new CountDownLatch(1);

        List<Long> counts =
                Collections.synchronizedList(
                        new ArrayList<>(
                                attempts
                        )
                );

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(
                             16
                     )) {

            List<Future<?>> futures =
                    new ArrayList<>(
                            attempts
                    );

            for (int index = 0;
                 index < attempts;
                 index++) {

                futures.add(
                        executor.submit(() -> {
                            if (!start.await(
                                    10L,
                                    TimeUnit.SECONDS
                            )) {
                                throw new IllegalStateException(
                                        "Concurrent Redis test "
                                                + "start timeout"
                                );
                            }

                            RateLimitResult result =
                                    rateLimiter
                                            .incrementAndGet(
                                                    KEY,
                                                    window
                                            );

                            counts.add(
                                    result.count()
                            );

                            return null;
                        })
                );
            }

            start.countDown();

            for (Future<?> future : futures) {
                future.get(
                        20L,
                        TimeUnit.SECONDS
                );
            }
        }

        List<Long> sorted =
                counts.stream()
                        .sorted()
                        .toList();

        List<Long> expected =
                java.util.stream.LongStream
                        .rangeClosed(
                                1L,
                                attempts
                        )
                        .boxed()
                        .toList();

        assertThat(sorted)
                .containsExactlyElementsOf(
                        expected
                );

        assertThat(
                redisTemplate
                        .opsForValue()
                        .get(KEY)
        ).isEqualTo(
                Integer.toString(
                        attempts
                )
        );

        Long ttlMillis =
                redisTemplate.getExpire(
                        KEY,
                        TimeUnit.MILLISECONDS
                );

        assertThat(ttlMillis)
                .isNotNull()
                .isPositive()
                .isLessThanOrEqualTo(
                        window.toMillis()
                );
    }

    @Test
    void blankKeyIsRejectedBeforeRedisOperation() {
        assertThatThrownBy(() ->
                rateLimiter.incrementAndGet(
                        " ",
                        Duration.ofSeconds(1)
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "Redis key"
                );
    }

    @Test
    void zeroTtlIsRejectedBeforeRedisOperation() {
        assertThatThrownBy(() ->
                rateLimiter.incrementAndGet(
                        KEY,
                        Duration.ZERO
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "ttl"
                );
    }

    @Test
    void negativeTtlIsRejectedBeforeRedisOperation() {
        assertThatThrownBy(() ->
                rateLimiter.incrementAndGet(
                        KEY,
                        Duration.ofMillis(-1L)
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "ttl"
                );
    }

    @Test
    void nullTtlIsRejectedBeforeRedisOperation() {
        assertThatThrownBy(() ->
                rateLimiter.incrementAndGet(
                        KEY,
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "ttl"
                );
    }
}