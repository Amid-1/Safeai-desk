package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserStatusCacheProductionInvariantVerifierTest {

    private static final DefaultApplicationArguments EMPTY_ARGS =
            new DefaultApplicationArguments();

    private static final Duration CACHE_TTL =
            Duration.ofSeconds(60);

    private static final String CACHE_KEY_PREFIX =
            "safeai:user-status";

    @Test
    void productionRejectsEnabledSecurityStatusCache() {
        UserStatusCacheProductionInvariantVerifier verifier =
                createVerifier(true);

        assertThatThrownBy(() -> verifier.run(EMPTY_ARGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "user-status-cache.enabled=true"
                );
    }

    @Test
    void productionAcceptsDisabledSecurityStatusCache() {
        UserStatusCacheProductionInvariantVerifier verifier =
                createVerifier(false);

        assertThatCode(() -> verifier.run(EMPTY_ARGS))
                .doesNotThrowAnyException();
    }

    private static UserStatusCacheProductionInvariantVerifier createVerifier(
            boolean enabled
    ) {
        UserStatusCacheProperties properties =
                new UserStatusCacheProperties(
                        enabled,
                        CACHE_TTL,
                        CACHE_KEY_PREFIX
                );

        return new UserStatusCacheProductionInvariantVerifier(
                properties
        );
    }
}