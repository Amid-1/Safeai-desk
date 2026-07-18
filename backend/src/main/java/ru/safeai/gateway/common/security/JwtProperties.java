package ru.safeai.gateway.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        long expirationMinutes,
        String issuer,
        String audience
) {
    private static final int MIN_HS256_SECRET_BYTES = 32;
    private static final long MAX_EXPIRATION_MINUTES = 60;

    public JwtProperties {
        secret = requireText(secret, "SAFEAI_JWT_SECRET не задан");

        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_HS256_SECRET_BYTES) {
            throw new IllegalStateException(
                    "SAFEAI_JWT_SECRET должен быть минимум 32 байта для HS256"
            );
        }

        if (expirationMinutes < 1 || expirationMinutes > MAX_EXPIRATION_MINUTES) {
            throw new IllegalStateException(
                    "JWT expirationMinutes должен быть в диапазоне 1–60"
            );
        }

        issuer = requireText(
                issuer,
                "JWT issuer не задан. Укажите app.security.jwt.issuer"
        );

        audience = requireText(
                audience,
                "JWT audience не задан. Укажите app.security.jwt.audience"
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }

        String normalized = value.trim();

        if (!normalized.equals(value)) {
            throw new IllegalStateException(message + ": значение содержит внешние пробелы");
        }

        return normalized;
    }
}
