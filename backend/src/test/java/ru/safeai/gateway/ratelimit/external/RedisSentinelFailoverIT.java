package ru.safeai.gateway.ratelimit.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import ru.safeai.gateway.ratelimit.DualRateLimitResult;
import ru.safeai.gateway.ratelimit.RedisFixedWindowRateLimiter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(
        named = "SAFEAI_TEST_REDIS_SENTINELS",
        matches = ".+"
)
@EnabledIfEnvironmentVariable(
        named = "SAFEAI_TEST_REDIS_SENTINEL_MASTER",
        matches = ".+"
)
class RedisSentinelFailoverIT {

    private static final String REDIS_SENTINELS_ENV =
            "SAFEAI_TEST_REDIS_SENTINELS";

    private static final String REDIS_SENTINEL_MASTER_ENV =
            "SAFEAI_TEST_REDIS_SENTINEL_MASTER";

    private static final String REDIS_PASSWORD_ENV =
            "SAFEAI_TEST_REDIS_PASSWORD";

    private static final Duration RATE_LIMIT_WINDOW =
            Duration.ofMinutes(10);

    private static final Duration FAILOVER_TIMEOUT =
            Duration.ofSeconds(60);

    private static final Duration FAILOVER_POLL_INTERVAL =
            Duration.ofMillis(500);

    private static final int SOCKET_TIMEOUT_MILLIS =
            5_000;

    @Test
    void rateLimiterRecoversAfterSentinelFailover()
            throws Exception {
        List<HostPort> sentinels = Arrays.stream(
                        requiredEnv(
                                REDIS_SENTINELS_ENV
                        ).split(",")
                )
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(HostPort::parse)
                .toList();

        String masterName =
                requiredEnv(
                        REDIS_SENTINEL_MASTER_ENV
                );

        RedisSentinelConfiguration configuration =
                new RedisSentinelConfiguration()
                        .master(masterName);

        for (HostPort sentinel : sentinels) {
            configuration.sentinel(
                    sentinel.host(),
                    sentinel.port()
            );
        }

        String password =
                System.getenv(
                        REDIS_PASSWORD_ENV
                );

        if (password != null && !password.isBlank()) {
            configuration.setPassword(
                    RedisPassword.of(password)
            );
        }

        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(
                        configuration
                );

        factory.afterPropertiesSet();
        factory.start();

        try {
            StringRedisTemplate template =
                    new StringRedisTemplate(factory);

            template.afterPropertiesSet();

            RedisFixedWindowRateLimiter limiter =
                    new RedisFixedWindowRateLimiter(
                            template
                    );

            assertThat(
                    attempt(
                            limiter,
                            "before"
                    ).allowed()
            )
                    .as(
                            "Initial rate-limit operation "
                                    + "must succeed before failover"
                    )
                    .isTrue();

            requestFailover(
                    sentinels.getFirst(),
                    masterName
            );

            await()
                    .alias(
                            "Lettuce connection recovery "
                                    + "after Redis Sentinel failover"
                    )
                    .atMost(
                            FAILOVER_TIMEOUT
                    )
                    .pollInterval(
                            FAILOVER_POLL_INTERVAL
                    )
                    .ignoreExceptions()
                    .untilAsserted(() ->
                            assertThat(
                                    attempt(
                                            limiter,
                                            "after-"
                                                    + System.nanoTime()
                                    ).allowed()
                            )
                                    .as(
                                            "Rate limiter operation "
                                                    + "must succeed through "
                                                    + "the promoted Redis primary"
                                    )
                                    .isTrue()
                    );
        } finally {
            factory.destroy();
        }
    }

    private DualRateLimitResult attempt(
            RedisFixedWindowRateLimiter limiter,
            String suffix
    ) {
        String email =
                "sentinel:{login}:email:"
                        + suffix;

        String ip =
                "sentinel:{login}:ip:"
                        + suffix;

        return limiter.incrementBothAndCheck(
                email,
                email + ":exceeded",
                10,
                ip,
                ip + ":exceeded",
                100,
                RATE_LIMIT_WINDOW
        );
    }

    private void requestFailover(
            HostPort sentinel,
            String masterName
    ) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(
                            sentinel.host(),
                            sentinel.port()
                    ),
                    SOCKET_TIMEOUT_MILLIS
            );

            socket.setSoTimeout(
                    SOCKET_TIMEOUT_MILLIS
            );

            OutputStream output =
                    socket.getOutputStream();

            byte[] masterBytes =
                    masterName.getBytes(
                            StandardCharsets.UTF_8
                    );

            String request =
                    "*3\r\n"
                            + "$8\r\nSENTINEL\r\n"
                            + "$8\r\nFAILOVER\r\n"
                            + "$"
                            + masterBytes.length
                            + "\r\n"
                            + masterName
                            + "\r\n";

            output.write(
                    request.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            output.flush();

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );

            String response =
                    reader.readLine();

            if (response == null
                    || !response.startsWith("+OK")) {
                throw new IllegalStateException(
                        "Sentinel FAILOVER rejected: "
                                + response
                );
            }
        }
    }

    private static String requiredEnv(
            String name
    ) {
        String value =
                System.getenv(name);

        assumeTrue(
                value != null && !value.isBlank(),
                "External Redis Sentinel test skipped: "
                        + "missing environment variable "
                        + name
        );

        return value;
    }

    private record HostPort(
            String host,
            int port
    ) {

        private static HostPort parse(
                String value
        ) {
            int separator =
                    value.lastIndexOf(':');

            if (separator <= 0
                    || separator == value.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid host:port: "
                                + value
                );
            }

            String host =
                    value.substring(
                            0,
                            separator
                    );

            int port =
                    parsePort(
                            value,
                            separator
                    );

            return new HostPort(
                    host,
                    port
            );
        }

        private static int parsePort(
                String value,
                int separator
        ) {
            int port;

            try {
                port = Integer.parseInt(
                        value.substring(
                                separator + 1
                        )
                );
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Invalid Sentinel port: "
                                + value,
                        exception
                );
            }

            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "Sentinel port is out of range: "
                                + value
                );
            }

            return port;
        }
    }
}