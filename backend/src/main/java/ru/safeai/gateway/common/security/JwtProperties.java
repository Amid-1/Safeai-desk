package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NullUnmarked;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Locale;

@NullUnmarked
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        long expirationMinutes,
        String issuer,
        String audience
) {
    private static final int MIN_HS256_SECRET_BYTES = 32;
    private static final int MAX_SECRET_BYTES = 128;
    private static final long MAX_EXPIRATION_MINUTES = 60;

    public JwtProperties {
        secret = requireText(
                secret,
                "SAFEAI_JWT_SECRET не задан"
        );

        byte[] keyBytes = decodeSecret(secret);

        if (keyBytes.length < MIN_HS256_SECRET_BYTES) {
            throw new IllegalStateException(
                    "SAFEAI_JWT_SECRET должен содержать минимум "
                            + "32 случайных байта после Base64-декодирования"
            );
        }

        if (keyBytes.length > MAX_SECRET_BYTES) {
            throw new IllegalStateException(
                    "SAFEAI_JWT_SECRET имеет недопустимо большой размер"
            );
        }

        if (expirationMinutes < 1L
                || expirationMinutes
                > MAX_EXPIRATION_MINUTES) {
            throw new IllegalStateException(
                    "JWT expirationMinutes должен быть в диапазоне 1–60"
            );
        }

        issuer = validateIssuer(
                requireText(
                        issuer,
                        "JWT issuer не задан. "
                                + "Укажите app.security.jwt.issuer"
                )
        );

        audience = requireText(
                audience,
                "JWT audience не задан. "
                        + "Укажите app.security.jwt.audience"
        );

        if (audience.length() > 255) {
            throw new IllegalStateException(
                    "JWT audience не должен превышать 255 символов"
            );
        }
    }

    public SecretKey secretKey() {
        return new SecretKeySpec(
                decodeSecret(secret),
                "HmacSHA256"
        );
    }

    private static byte[] decodeSecret(
            String value
    ) {
        try {
            return Base64
                    .getDecoder()
                    .decode(value);
        } catch (IllegalArgumentException standardFailure) {
            try {
                return Base64
                        .getUrlDecoder()
                        .decode(value);
            } catch (IllegalArgumentException urlFailure) {
                throw new IllegalStateException(
                        "SAFEAI_JWT_SECRET должен быть Base64/Base64URL-строкой "
                                + "из криптографически случайных байтов",
                        urlFailure
                );
            }
        }
    }

    private static String validateIssuer(
            String value
    ) {
        try {
            URI uri = new URI(value);

            String scheme =
                    uri.getScheme() == null
                            ? ""
                            : uri.getScheme()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            if (!("http".equals(scheme)
                    || "https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalStateException(
                        "JWT issuer должен быть абсолютным HTTP(S) URI "
                                + "без query/fragment/userInfo"
                );
            }

            return value;
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    "JWT issuer имеет некорректный URI",
                    exception
            );
        }
    }

    private static String requireText(
            String value,
            String message
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    message
            );
        }

        String normalized = value.trim();

        if (!normalized.equals(value)) {
            throw new IllegalStateException(
                    message
                            + ": значение содержит внешние пробелы"
            );
        }

        return normalized;
    }
}
