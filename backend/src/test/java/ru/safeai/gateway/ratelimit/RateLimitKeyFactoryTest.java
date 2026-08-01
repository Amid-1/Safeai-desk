package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitKeyFactoryTest {

    private static final String SECRET_A =
            "0123456789abcdef0123456789abcdef";

    private static final String SECRET_B =
            "abcdef0123456789abcdef0123456789";

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    @Test
    void keysAreDeterministicAndDoNotContainRawIdentity() {
        RateLimitKeyFactory factory =
                factory(SECRET_A, "v1");

        String emailKey =
                factory.loginEmail(
                        "admin@example.com"
                );

        String ipKey =
                factory.loginIp(
                        "203.0.113.10"
                );

        assertThat(emailKey)
                .isEqualTo(
                        factory.loginEmail(
                                "admin@example.com"
                        )
                )
                .startsWith(
                        "safeai:test:v1:rate-limit:{login}:email:"
                )
                .doesNotContain("admin@example.com");

        assertThat(ipKey)
                .startsWith(
                        "safeai:test:v1:rate-limit:{login}:ip:"
                )
                .doesNotContain("203.0.113.10");
    }

    @Test
    void hmacDomainsSeparateEmailAndIp() {
        RateLimitKeyFactory factory =
                factory(SECRET_A, "v1");

        assertThat(
                factory.loginEmail("same-value")
        ).isNotEqualTo(
                factory.loginIp("same-value")
        );
    }

    @Test
    void loginCounterAndMarkersShareClusterSlot() {
        RateLimitKeyFactory factory =
                factory(SECRET_A, "v1");

        String email =
                factory.loginEmail(
                        "admin@example.com"
                );

        String ip =
                factory.loginIp(
                        "203.0.113.10"
                );

        assertThat(hashTag(email))
                .isEqualTo("login")
                .isEqualTo(hashTag(ip))
                .isEqualTo(
                        hashTag(
                                factory.exceededMarker(email)
                        )
                )
                .isEqualTo(
                        hashTag(
                                factory.exceededMarker(ip)
                        )
                );
    }

    @Test
    void aiUserAndOrganizationShareOrganizationScopedClusterSlot() {
        RateLimitKeyFactory factory =
                factory(SECRET_A, "v1");

        String userKey =
                factory.aiMessageUser(
                        ORGANIZATION_ID,
                        USER_ID
                );

        String organizationKey =
                factory.aiMessageOrganization(
                        ORGANIZATION_ID
                );

        assertThat(hashTag(userKey))
                .isEqualTo(hashTag(organizationKey))
                .startsWith("ai-");

        assertThat(hashTag(
                factory.aiMessageOrganization(
                        OTHER_ORGANIZATION_ID
                )
        )).isNotEqualTo(hashTag(organizationKey));
    }

    @Test
    void secretRotationAndVersioningChangeKeys() {
        String first = factory(SECRET_A, "v1")
                .loginEmail("admin@example.com");

        String rotatedSecret = factory(SECRET_B, "v1")
                .loginEmail("admin@example.com");

        String nextVersion = factory(SECRET_A, "v2")
                .loginEmail("admin@example.com");

        assertThat(rotatedSecret)
                .isNotEqualTo(first);

        assertThat(nextVersion)
                .isNotEqualTo(first)
                .contains(":v2:");
    }

    @Test
    void nullIdsAndBlankIdentityAreRejected() {
        RateLimitKeyFactory factory =
                factory(SECRET_A, "v1");

        assertThatThrownBy(() ->
                factory.aiMessageUser(
                        null,
                        USER_ID
                )
        ).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() ->
                factory.aiMessageOrganization(null)
        ).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() ->
                factory.loginEmail(" ")
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private RateLimitKeyFactory factory(
            String secret,
            String version
    ) {
        return new RateLimitKeyFactory(
                new RateLimitRedisKeyProperties(
                        "safeai:test",
                        secret,
                        version
                )
        );
    }

    private String hashTag(
            String key
    ) {
        int start = key.indexOf('{');
        int end = key.indexOf('}', start + 1);

        if (start < 0 || end <= start + 1) {
            throw new AssertionError(
                    "Redis hash tag not found: " + key
            );
        }

        return key.substring(start + 1, end);
    }
}
