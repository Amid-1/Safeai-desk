package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserStatusCacheProductionInvariantVerifierTest {

    private static final DefaultApplicationArguments EMPTY_ARGS =
            new DefaultApplicationArguments();

    @Test
    void productionRejectsEnabledSecurityStatusCache() {
        UserStatusCacheProductionInvariantVerifier verifier =
                new UserStatusCacheProductionInvariantVerifier(
                        new UserStatusCacheProperties(
                                true,
                                Duration.ofSeconds(
                                        60
                                ),
                                "safeai:user-status"
                        )
                );

        assertThatThrownBy(() ->
                verifier.run(
                        EMPTY_ARGS
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "user-status-cache.enabled=true"
                );
    }

    @Test
    void productionAcceptsDisabledSecurityStatusCache() {
        UserStatusCacheProductionInvariantVerifier verifier =
                new UserStatusCacheProductionInvariantVerifier(
                        new UserStatusCacheProperties(
                                false,
                                Duration.ofSeconds(
                                        60
                                ),
                                "safeai:user-status"
                        )
                );

        assertThatCode(() ->
                verifier.run(
                        EMPTY_ARGS
                )
        ).doesNotThrowAnyException();
    }
}