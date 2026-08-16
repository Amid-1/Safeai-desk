package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final String ACTIVE_KEY_ID =
            "active-2026-08";

    private static final String PREVIOUS_KEY_ID =
            "previous-2026-07";

    private static final String PUBLIC_KEY =
            String.join(
                    "\n",
                    "-----BEGIN PUBLIC KEY-----",
                    "test-public-key",
                    "-----END PUBLIC KEY-----"
            );

    private static final String PRIVATE_KEY =
            String.join(
                    "\n",
                    "-----BEGIN PRIVATE KEY-----",
                    "test-private-key",
                    "-----END PRIVATE KEY-----"
            );

    private static final String PREVIOUS_PUBLIC_KEY =
            String.join(
                    "\n",
                    "-----BEGIN PUBLIC KEY-----",
                    "previous-test-public-key",
                    "-----END PUBLIC KEY-----"
            );

    @Test
    void validRs256ConfigurationIsAccepted() {
        JwtProperties properties =
                properties(
                        "https://safeai.example.com"
                );

        assertThat(
                properties.expirationMinutes()
        ).isEqualTo(15L);

        assertThat(
                properties.issuer()
        ).isEqualTo(
                "https://safeai.example.com"
        );

        assertThat(
                properties.audience()
        ).isEqualTo(
                "safeai-desk-api"
        );

        assertThat(
                properties.activeKeyId()
        ).isEqualTo(
                ACTIVE_KEY_ID
        );

        assertThat(
                properties.keys()
        ).containsExactly(
                new JwtProperties.KeyEntry(
                        ACTIVE_KEY_ID,
                        PUBLIC_KEY,
                        PRIVATE_KEY
                ),
                new JwtProperties.KeyEntry(
                        PREVIOUS_KEY_ID,
                        PREVIOUS_PUBLIC_KEY,
                        null
                )
        );
    }

    @Test
    void previousVerificationOnlyKeyIsAccepted() {
        JwtProperties properties =
                new JwtProperties(
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api",
                        ACTIVE_KEY_ID,
                        List.of(
                                activeKey(),
                                new JwtProperties.KeyEntry(
                                        PREVIOUS_KEY_ID,
                                        PREVIOUS_PUBLIC_KEY,
                                        null
                                )
                        )
                );

        JwtProperties.KeyEntry previousKey =
                properties.keys()
                        .get(1);

        assertThat(
                previousKey.id()
        ).isEqualTo(
                PREVIOUS_KEY_ID
        );

        assertThat(
                previousKey.publicKey()
        ).isEqualTo(
                PREVIOUS_PUBLIC_KEY
        );

        assertThat(
                previousKey.privateKey()
        ).isNull();
    }

    @Test
    void activeKeyWithoutPrivateKeyIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api",
                        ACTIVE_KEY_ID,
                        List.of(
                                new JwtProperties.KeyEntry(
                                        ACTIVE_KEY_ID,
                                        PUBLIC_KEY,
                                        null
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "private-key"
                )
                .hasMessageContaining(
                        ACTIVE_KEY_ID
                );
    }

    @Test
    void activeKeyIdMustExistInKeyRing() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api",
                        "missing-key",
                        List.of(
                                activeKey()
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "не найден"
                )
                .hasMessageContaining(
                        "missing-key"
                );
    }

    @Test
    void emptyKeyRingIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api",
                        ACTIVE_KEY_ID,
                        List.of()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "хотя бы один RSA key"
                );
    }

    @Test
    void duplicateKeyIdsAreRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api",
                        ACTIVE_KEY_ID,
                        List.of(
                                activeKey(),
                                new JwtProperties.KeyEntry(
                                        ACTIVE_KEY_ID,
                                        PREVIOUS_PUBLIC_KEY,
                                        null
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "Повторяющийся JWT key id"
                )
                .hasMessageContaining(
                        ACTIVE_KEY_ID
                );
    }

    @Test
    void invalidActiveKeyIdIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api",
                        "invalid key id",
                        List.of(
                                activeKey()
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "active-key-id"
                )
                .hasMessageContaining(
                        "[A-Za-z0-9._-]{1,64}"
                );
    }

    @Test
    void invalidKeyEntryIdIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties.KeyEntry(
                        "invalid key id",
                        PUBLIC_KEY,
                        PRIVATE_KEY
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "keys[].id"
                )
                .hasMessageContaining(
                        "[A-Za-z0-9._-]{1,64}"
                );
    }

    @Test
    void blankPublicKeyIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties.KeyEntry(
                        ACTIVE_KEY_ID,
                        " ",
                        PRIVATE_KEY
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "public-key"
                );
    }

    @Test
    void blankPrivateKeyIsNormalizedToNullForPreviousKey() {
        JwtProperties.KeyEntry key =
                new JwtProperties.KeyEntry(
                        PREVIOUS_KEY_ID,
                        PREVIOUS_PUBLIC_KEY,
                        "   "
                );

        assertThat(
                key.privateKey()
        ).isNull();
    }

    @Test
    void expirationBelowMinimumIsRejected() {
        assertInvalidExpiration(0L);
    }

    @Test
    void expirationAboveMaximumIsRejected() {
        assertInvalidExpiration(61L);
    }

    @Test
    void nonUriIssuerIsRejected() {
        assertInvalidIssuer(
                "safeai-desk"
        );
    }

    @Test
    void issuerWithQueryIsRejected() {
        assertInvalidIssuer(
                "https://safeai.example.com?x=1"
        );
    }

    @Test
    void issuerWithFragmentIsRejected() {
        assertInvalidIssuer(
                "https://safeai.example.com#x"
        );
    }

    @Test
    void issuerWithUserInfoIsRejected() {
        assertInvalidIssuer(
                "https://user@safeai.example.com"
        );
    }

    @Test
    void unsupportedIssuerSchemeIsRejected() {
        assertInvalidIssuer(
                "ftp://safeai.example.com"
        );
    }

    @Test
    void issuerWithPathIsAccepted() {
        JwtProperties properties =
                properties(
                        "https://safeai.example.com/auth"
                );

        assertThat(
                properties.issuer()
        ).isEqualTo(
                "https://safeai.example.com/auth"
        );
    }

    @Test
    void audienceLongerThan255CharactersIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        15L,
                        "https://safeai.example.com",
                        "a".repeat(256),
                        ACTIVE_KEY_ID,
                        List.of(
                                activeKey()
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "255"
                );
    }

    private void assertInvalidExpiration(
            long minutes
    ) {
        assertThatThrownBy(() ->
                new JwtProperties(
                        minutes,
                        "https://safeai.example.com",
                        "safeai-desk-api",
                        ACTIVE_KEY_ID,
                        List.of(
                                activeKey()
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "1–60"
                );
    }

    private void assertInvalidIssuer(
            String issuer
    ) {
        assertThatThrownBy(() ->
                new JwtProperties(
                        15L,
                        issuer,
                        "safeai-desk-api",
                        ACTIVE_KEY_ID,
                        List.of(
                                activeKey()
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "issuer"
                );
    }

    private JwtProperties properties(
            String issuer
    ) {
        return new JwtProperties(
                15L,
                issuer,
                "safeai-desk-api",
                ACTIVE_KEY_ID,
                List.of(
                        activeKey(),
                        new JwtProperties.KeyEntry(
                                PREVIOUS_KEY_ID,
                                PREVIOUS_PUBLIC_KEY,
                                null
                        )
                )
        );
    }

    private JwtProperties.KeyEntry activeKey() {
        return new JwtProperties.KeyEntry(
                ACTIVE_KEY_ID,
                PUBLIC_KEY,
                PRIVATE_KEY
        );
    }
}