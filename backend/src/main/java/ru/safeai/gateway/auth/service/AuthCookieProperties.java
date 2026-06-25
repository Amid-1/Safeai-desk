package ru.safeai.gateway.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.auth.cookies")
public record AuthCookieProperties(
        boolean secure,
        String sameSite,
        Duration accessTokenMaxAge,
        Duration refreshTokenMaxAge
) {
    public AuthCookieProperties {
        sameSite = normalizeSameSite(sameSite);

        if (accessTokenMaxAge == null || accessTokenMaxAge.isZero() || accessTokenMaxAge.isNegative()) {
            accessTokenMaxAge = Duration.ofMinutes(15);
        }

        if (refreshTokenMaxAge == null || refreshTokenMaxAge.isZero() || refreshTokenMaxAge.isNegative()) {
            refreshTokenMaxAge = Duration.ofDays(30);
        }

        if (!refreshTokenMaxAge.minus(accessTokenMaxAge).isPositive()) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.refresh-token-max-age должен быть больше access-token-max-age"
            );
        }

        if ("None".equals(sameSite) && !secure) {
            throw new IllegalStateException(
                    "SameSite=None требует Secure=true. " +
                            "Используй SAFEAI_AUTH_COOKIES_SECURE=true или SameSite=Lax"
            );
        }
    }

    private static String normalizeSameSite(String value) {
        if (value == null || value.isBlank()) {
            return "Lax";
        }

        return switch (value.trim().toLowerCase()) {
            case "strict" -> "Strict";
            case "lax" -> "Lax";
            case "none" -> "None";
            default -> throw new IllegalStateException(
                    "Недопустимое значение safeai.auth.cookies.same-site: " + value
            );
        };
    }
}