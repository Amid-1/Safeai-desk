package ru.safeai.gateway.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "safeai.auth.cookies")
public record AuthCookieProperties(
        boolean secure,
        String sameSite,
        Duration accessTokenMaxAge,
        Duration refreshTokenMaxAge,
        String domain
) {
    private static final Pattern DOMAIN_PATTERN =
            Pattern.compile("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$");

    public AuthCookieProperties {
        sameSite = normalizeSameSite(sameSite);
        domain = normalizeDomain(domain);

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

    public boolean hasDomain() {
        return domain != null && !domain.isBlank();
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

    private static String normalizeDomain(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().toLowerCase();

        validateDomainOnly(normalized, value);

        normalized = removeLeadingDot(normalized);

        validateDomainPattern(normalized, value);

        return normalized;
    }

    private static void validateDomainOnly(String normalized, String originalValue) {
        if (normalized.contains("://")
                || normalized.contains("/")
                || normalized.contains(":")
                || normalized.contains(" ")
                || normalized.contains("\t")
                || normalized.contains("\n")
                || normalized.contains("\r")) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.domain должен быть domain-only, без scheme, path и port: " + originalValue
            );
        }
    }

    private static String removeLeadingDot(String normalized) {
        if (normalized.startsWith(".")) {
            return normalized.substring(1);
        }

        return normalized;
    }

    private static void validateDomainPattern(String normalized, String originalValue) {
        if (normalized.isBlank() || !DOMAIN_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    "Недопустимое значение safeai.auth.cookies.domain: " + originalValue
            );
        }
    }
}