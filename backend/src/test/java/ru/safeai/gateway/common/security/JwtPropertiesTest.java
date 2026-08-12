package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    @Test
    void validBase64SecretAndHttpsIssuerAreAccepted() {
        JwtProperties properties =
                new JwtProperties(
                        secretOfBytes(32),
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api"
                );

        assertThat(
                properties.secretKey()
                        .getEncoded()
        ).hasSize(32);

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
    }

    @Test
    void validBase64UrlSecretIsAccepted() {
        String secret =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                new byte[32]
                        );

        JwtProperties properties =
                new JwtProperties(
                        secret,
                        15L,
                        "http://localhost:8080",
                        "safeai-desk-api"
                );

        assertThat(
                properties.secretKey()
                        .getEncoded()
        ).hasSize(32);
    }

    @Test
    void decodedSecretShorterThan32BytesIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        secretOfBytes(31),
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "минимум 32"
                );
    }

    @Test
    void decodedSecretLongerThan128BytesIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        secretOfBytes(129),
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "недопустимо большой"
                );
    }

    @Test
    void nonBase64SecretIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        "not base64 !!!",
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "Base64"
                );
    }

    @Test
    void missingSecretIsRejected() {
        assertThatThrownBy(() ->
                new JwtProperties(
                        null,
                        15L,
                        "https://safeai.example.com",
                        "safeai-desk-api"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "SAFEAI_JWT_SECRET"
                );
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
                new JwtProperties(
                        secretOfBytes(32),
                        15L,
                        "https://safeai.example.com/auth",
                        "safeai-desk-api"
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
                        secretOfBytes(32),
                        15L,
                        "https://safeai.example.com",
                        "a".repeat(256)
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
                        secretOfBytes(32),
                        minutes,
                        "https://safeai.example.com",
                        "safeai-desk-api"
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
                        secretOfBytes(32),
                        15L,
                        issuer,
                        "safeai-desk-api"
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "issuer"
                );
    }

    private String secretOfBytes(
            int size
    ) {
        return Base64.getEncoder()
                .encodeToString(
                        new byte[size]
                );
    }
}
