package ru.safeai.gateway.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "safeai.auth.cookies")
public record AuthCookieProperties(
        boolean secure,
        String sameSite,
        Duration accessTokenMaxAge,
        Duration refreshTokenMaxAge,
        String domain
) {
    private static final Duration MAX_ACCESS_TOKEN_MAX_AGE =
            Duration.ofMinutes(60);

    private static final Duration MAX_REFRESH_TOKEN_MAX_AGE =
            Duration.ofDays(90);

    private static final Pattern DOMAIN_PATTERN =
            Pattern.compile("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$");

    public AuthCookieProperties {
        sameSite = normalizeSameSite(sameSite);
        domain = normalizeDomain(domain);

        requirePositive(
                accessTokenMaxAge,
                "safeai.auth.cookies.access-token-max-age"
        );

        requirePositive(
                refreshTokenMaxAge,
                "safeai.auth.cookies.refresh-token-max-age"
        );

        if (accessTokenMaxAge.compareTo(MAX_ACCESS_TOKEN_MAX_AGE) > 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.access-token-max-age "
                            + "не должен превышать 60 минут"
            );
        }

        if (refreshTokenMaxAge.compareTo(MAX_REFRESH_TOKEN_MAX_AGE) > 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.refresh-token-max-age "
                            + "не должен превышать 90 дней"
            );
        }

        if (refreshTokenMaxAge.compareTo(accessTokenMaxAge) <= 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.refresh-token-max-age "
                            + "должен быть больше access-token-max-age"
            );
        }

        if ("None".equals(sameSite) && !secure) {
            throw new IllegalStateException(
                    "SameSite=None требует Secure=true. "
                            + "Используй SAFEAI_AUTH_COOKIES_SECURE=true "
                            + "или SameSite=Lax"
            );
        }
    }

    public boolean hasDomain() {
        return domain != null && !domain.isBlank();
    }

    private static void requirePositive(
            Duration value,
            String propertyName
    ) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    propertyName + " должен быть положительным"
            );
        }
    }

    private static String normalizeSameSite(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.same-site не задан"
            );
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "strict" -> "Strict";
            case "lax" -> "Lax";
            case "none" -> "None";
            default -> throw new IllegalStateException(
                    "Недопустимое значение "
                            + "safeai.auth.cookies.same-site: "
                            + value
            );
        };
    }

    private static String normalizeDomain(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value
                .trim()
                .toLowerCase(Locale.ROOT);

        validateDomainOnly(normalized, value);

        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }

        if (normalized.isBlank()
                || !DOMAIN_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    "Недопустимое значение "
                            + "safeai.auth.cookies.domain: "
                            + value
            );
        }

        return normalized;
    }

    private static void validateDomainOnly(
            String normalized,
            String originalValue
    ) {
        if (normalized.contains("://")
                || normalized.contains("/")
                || normalized.contains(":")
                || normalized.contains(" ")
                || normalized.contains("\t")
                || normalized.contains("\n")
                || normalized.contains("\r")) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.domain должен быть domain-only, "
                            + "без scheme, path и port: "
                            + originalValue
            );
        }
    }
}