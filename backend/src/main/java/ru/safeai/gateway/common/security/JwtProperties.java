package ru.safeai.gateway.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        long expirationMinutes,
        String issuer
) {
    public JwtProperties {
        if (expirationMinutes <= 0) {
            expirationMinutes = 60;
        }

        if (issuer == null || issuer.isBlank()) {
            issuer = "safeai-desk";
        }
    }

    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("SAFEAI_JWT_SECRET не задан");
        }

        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("SAFEAI_JWT_SECRET должен быть минимум 32 байта для HS256");
        }
    }
}