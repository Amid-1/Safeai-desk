package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final String ISSUER =
            "https://safeai.example.com";

    private static final String AUDIENCE =
            "safeai-api";

    private static final long EXPIRATION_MINUTES = 15L;

    @Test
    void validPropertiesArePreserved() {
        String secret = standardBase64Secret(32);

        JwtProperties properties = new JwtProperties(
                secret,
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        );

        assertThat(properties.secret()).isEqualTo(secret);
        assertThat(properties.expirationMinutes())
                .isEqualTo(EXPIRATION_MINUTES);
        assertThat(properties.issuer()).isEqualTo(ISSUER);
        assertThat(properties.audience()).isEqualTo(AUDIENCE);
    }

    @Test
    void secretKeyIsCreatedFromDecodedSecret() {
        byte[] expectedKeyBytes = bytes(32);
        String encodedSecret = Base64.getEncoder()
                .encodeToString(expectedKeyBytes);

        JwtProperties properties = new JwtProperties(
                encodedSecret,
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        );

        SecretKey secretKey = properties.secretKey();

        assertThat(secretKey.getAlgorithm())
                .isEqualTo("HmacSHA256");
        assertThat(secretKey.getFormat())
                .isEqualTo("RAW");
        assertThat(secretKey.getEncoded())
                .containsExactly(expectedKeyBytes);
    }

    @Test
    void standardBase64SecretIsAccepted() {
        byte[] expectedKeyBytes = bytes(32);

        JwtProperties properties = new JwtProperties(
                Base64.getEncoder().encodeToString(expectedKeyBytes),
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        );

        assertThat(properties.secretKey().getEncoded())
                .containsExactly(expectedKeyBytes);
    }

    @Test
    void unpaddedBase64UrlSecretIsAccepted() {
        byte[] expectedKeyBytes = new byte[32];
        Arrays.fill(expectedKeyBytes, (byte) 0xFB);

        String encodedSecret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(expectedKeyBytes);

        assertThat(encodedSecret)
                .containsAnyOf("-", "_");

        JwtProperties properties = new JwtProperties(
                encodedSecret,
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        );

        assertThat(properties.secretKey().getEncoded())
                .containsExactly(expectedKeyBytes);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t",
            "\n"
    })
    void missingOrBlankSecretIsRejected(String secret) {
        assertThatThrownBy(() -> new JwtProperties(
                secret,
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "SAFEAI_JWT_SECRET не задан"
                );
    }

    @Test
    void secretWithLeadingWhitespaceIsRejected() {
        String secret = " " + standardBase64Secret(32);

        assertThatThrownBy(() -> new JwtProperties(
                secret,
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "значение содержит внешние пробелы"
                );
    }

    @Test
    void secretWithTrailingWhitespaceIsRejected() {
        String secret = standardBase64Secret(32) + " ";

        assertThatThrownBy(() -> new JwtProperties(
                secret,
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "значение содержит внешние пробелы"
                );
    }

    @Test
    void malformedBase64SecretIsRejected() {
        assertThatThrownBy(() -> new JwtProperties(
                "***not-a-base64-secret***",
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "должен быть Base64/Base64URL-строкой"
                );
    }

    @Test
    void secretShorterThan32DecodedBytesIsRejected() {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(31),
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("минимум 32");
    }

    @Test
    void secretWithExactly32DecodedBytesIsAccepted() {
        JwtProperties properties = new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        );

        assertThat(properties.secretKey().getEncoded())
                .hasSize(32);
    }

    @Test
    void secretWithExactly128DecodedBytesIsAccepted() {
        JwtProperties properties = new JwtProperties(
                standardBase64Secret(128),
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        );

        assertThat(properties.secretKey().getEncoded())
                .hasSize(128);
    }

    @Test
    void secretLongerThan128DecodedBytesIsRejected() {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(129),
                EXPIRATION_MINUTES,
                ISSUER,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "недопустимо большой размер"
                );
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1L,
            60L
    })
    void expirationBoundaryValuesAreAccepted(
            long expirationMinutes
    ) {
        JwtProperties properties = new JwtProperties(
                standardBase64Secret(32),
                expirationMinutes,
                ISSUER,
                AUDIENCE
        );

        assertThat(properties.expirationMinutes())
                .isEqualTo(expirationMinutes);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            -1L,
            0L,
            61L,
            Long.MAX_VALUE
    })
    void expirationOutsideAllowedRangeIsRejected(
            long expirationMinutes
    ) {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                expirationMinutes,
                ISSUER,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("диапазоне 1–60");
    }

    @Test
    void validHttpsIssuerIsAccepted() {
        JwtProperties properties = validProperties(
                "https://safeai.example.com"
        );

        assertThat(properties.issuer())
                .isEqualTo("https://safeai.example.com");
    }

    @Test
    void validHttpIssuerIsAcceptedByCurrentContract() {
        JwtProperties properties = validProperties(
                "http://localhost:8080"
        );

        assertThat(properties.issuer())
                .isEqualTo("http://localhost:8080");
    }

    @Test
    void issuerWithPathIsAcceptedByCurrentContract() {
        JwtProperties properties = validProperties(
                "https://safeai.example.com/oauth2"
        );

        assertThat(properties.issuer())
                .isEqualTo(
                        "https://safeai.example.com/oauth2"
                );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t"
    })
    void missingOrBlankIssuerIsRejected(String issuer) {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                issuer,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT issuer не задан");
    }

    @Test
    void issuerWithExternalWhitespaceIsRejected() {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                " https://safeai.example.com ",
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "значение содержит внешние пробелы"
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "safeai.example.com",
            "ftp://safeai.example.com",
            "https:///missing-host",
            "https://user@safeai.example.com",
            "https://safeai.example.com?tenant=1",
            "https://safeai.example.com#fragment"
    })
    void semanticallyInvalidIssuerIsRejected(String issuer) {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                issuer,
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "абсолютным HTTP(S) URI"
                );
    }

    @Test
    void syntacticallyMalformedIssuerIsRejected() {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                "https://exa mple.com",
                AUDIENCE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "issuer имеет некорректный URI"
                );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t"
    })
    void missingOrBlankAudienceIsRejected(String audience) {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                ISSUER,
                audience
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT audience не задан");
    }

    @Test
    void audienceWithLeadingWhitespaceIsRejected() {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                ISSUER,
                " safeai-api"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "значение содержит внешние пробелы"
                );
    }

    @Test
    void audienceWithTrailingWhitespaceIsRejected() {
        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                ISSUER,
                "safeai-api "
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "значение содержит внешние пробелы"
                );
    }

    @Test
    void audienceWithExactly255CharactersIsAccepted() {
        String audience = "a".repeat(255);

        JwtProperties properties = new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                ISSUER,
                audience
        );

        assertThat(properties.audience())
                .hasSize(255)
                .isEqualTo(audience);
    }

    @Test
    void audienceLongerThan255CharactersIsRejected() {
        String audience = "a".repeat(256);

        assertThatThrownBy(() -> new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                ISSUER,
                audience
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "не должен превышать 255 символов"
                );
    }

    private JwtProperties validProperties(String issuer) {
        return new JwtProperties(
                standardBase64Secret(32),
                EXPIRATION_MINUTES,
                issuer,
                AUDIENCE
        );
    }

    private String standardBase64Secret(int decodedLength) {
        return Base64.getEncoder()
                .encodeToString(bytes(decodedLength));
    }

    private byte[] bytes(int length) {
        byte[] result = new byte[length];

        for (int index = 0; index < length; index++) {
            result[index] = (byte) (
                    index * 31 + 7
            );
        }

        return result;
    }
}