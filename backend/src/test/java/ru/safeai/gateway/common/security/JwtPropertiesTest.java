package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final String VALID_SECRET =
            "1234567890123456789012345678901212345678901234567890123456789012";

    @Test
    void constructor_shouldThrowWhenSecretIsNull() {
        assertThatThrownBy(() ->
                new JwtProperties(null, 15, "issuer", "audience")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFEAI_JWT_SECRET");
    }

    @Test
    void constructor_shouldThrowWhenSecretIsBlank() {
        assertThatThrownBy(() ->
                new JwtProperties("   ", 15, "issuer", "audience")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFEAI_JWT_SECRET");
    }

    @Test
    void constructor_shouldThrowWhenSecretIsTooShort() {
        assertThatThrownBy(() ->
                new JwtProperties("short-secret", 15, "issuer", "audience")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("минимум 32 байта");
    }

    @Test
    void constructor_shouldRejectZeroNegativeAndTooLargeExpiration() {
        assertThatThrownBy(() ->
                new JwtProperties(VALID_SECRET, 0, "issuer", "audience")
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                new JwtProperties(VALID_SECRET, -10, "issuer", "audience")
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                new JwtProperties(VALID_SECRET, 61, "issuer", "audience")
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_shouldRejectBlankIssuer() {
        assertThatThrownBy(() ->
                new JwtProperties(VALID_SECRET, 15, "   ", "audience")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void constructor_shouldRejectBlankAudience() {
        assertThatThrownBy(() ->
                new JwtProperties(VALID_SECRET, 15, "issuer", "   ")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void constructor_shouldRejectExternalSpacesInIssuerAndAudience() {
        assertThatThrownBy(() ->
                new JwtProperties(VALID_SECRET, 15, " issuer", "audience")
        ).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() ->
                new JwtProperties(VALID_SECRET, 15, "issuer", "audience ")
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructor_shouldKeepValidValues() {
        JwtProperties properties = new JwtProperties(
                VALID_SECRET,
                60,
                "safeai-test",
                "safeai-test-api"
        );

        assertThat(properties.secret()).isEqualTo(VALID_SECRET);
        assertThat(properties.expirationMinutes()).isEqualTo(60);
        assertThat(properties.issuer()).isEqualTo("safeai-test");
        assertThat(properties.audience()).isEqualTo("safeai-test-api");
    }
}
