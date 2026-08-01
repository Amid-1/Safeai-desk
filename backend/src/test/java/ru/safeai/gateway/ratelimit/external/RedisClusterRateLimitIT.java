package ru.safeai.gateway.ratelimit.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import ru.safeai.gateway.ratelimit.DualRateLimitResult;
import ru.safeai.gateway.ratelimit.RateLimitKeyFactory;
import ru.safeai.gateway.ratelimit.RateLimitRedisKeyProperties;
import ru.safeai.gateway.ratelimit.RedisFixedWindowRateLimiter;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(
        named = "SAFEAI_TEST_REDIS_CLUSTER_NODES",
        matches = ".+"
)
class RedisClusterRateLimitIT {

    private static final String REDIS_CLUSTER_NODES_ENV =
            "SAFEAI_TEST_REDIS_CLUSTER_NODES";

    private static final String REDIS_PASSWORD_ENV =
            "SAFEAI_TEST_REDIS_PASSWORD";

    private static final String TEST_HMAC_SECRET =
            "0123456789abcdef0123456789abcdef";

    @Test
    void luaScriptsExecuteWithoutCrossSlot() {
        List<String> nodes = Arrays.stream(
                        requiredClusterNodes()
                                .split(",")
                )
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();

        RedisClusterConfiguration configuration =
                new RedisClusterConfiguration(nodes);

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

            String testKeyVersion =
                    UUID.randomUUID()
                            .toString()
                            .replace("-", "");

            RateLimitKeyFactory keys =
                    new RateLimitKeyFactory(
                            new RateLimitRedisKeyProperties(
                                    "safeai:cluster-it",
                                    TEST_HMAC_SECRET,
                                    testKeyVersion
                            )
                    );

            String email =
                    keys.loginEmail(
                            "cluster@example.com"
                    );

            String ip =
                    keys.loginIp(
                            "203.0.113.50"
                    );

            DualRateLimitResult login =
                    limiter.incrementBothAndCheck(
                            email,
                            keys.exceededMarker(email),
                            10,
                            ip,
                            keys.exceededMarker(ip),
                            100,
                            Duration.ofMinutes(10)
                    );

            assertThat(login.allowed())
                    .isTrue();

            UUID organizationId =
                    UUID.randomUUID();

            String user =
                    keys.aiMessageUser(
                            organizationId,
                            UUID.randomUUID()
                    );

            String organization =
                    keys.aiMessageOrganization(
                            organizationId
                    );

            DualRateLimitResult ai =
                    limiter.tryIncrementBoth(
                            user,
                            keys.exceededMarker(user),
                            10,
                            organization,
                            keys.exceededMarker(
                                    organization
                            ),
                            100,
                            Duration.ofHours(1)
                    );

            assertThat(ai.allowed())
                    .isTrue();
        } finally {
            factory.destroy();
        }
    }

    private static String requiredClusterNodes() {
    String value =
            System.getenv(
                    REDIS_CLUSTER_NODES_ENV
            );

    assumeTrue(
            value != null && !value.isBlank(),
            "External Redis Cluster test skipped: "
                    + "missing environment variable "
                    + REDIS_CLUSTER_NODES_ENV
    );

    return value;
}
}