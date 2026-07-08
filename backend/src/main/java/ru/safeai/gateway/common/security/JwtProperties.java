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
    private static final long DEFAULT_EXPIRATION_MINUTES = 15;
    private static final String DEFAULT_ISSUER = "safeai-desk";
    private static final String DEFAULT_AUDIENCE = "safeai-desk-api";
    private static final int MIN_HS256_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("SAFEAI_JWT_SECRET не задан");
        }

        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_HS256_SECRET_BYTES) {
            throw new IllegalStateException("SAFEAI_JWT_SECRET должен быть минимум 32 байта для HS256");
        }

        if (expirationMinutes <= 0) {
            expirationMinutes = DEFAULT_EXPIRATION_MINUTES;
        }

        if (issuer == null || issuer.isBlank()) {
            issuer = DEFAULT_ISSUER;
        }

        if (audience == null || audience.isBlank()) {
            audience = DEFAULT_AUDIENCE;
        }
    }
}