package ru.safeai.gateway.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.IDN;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "safeai.auth.cookies")
public record AuthCookieProperties(
        boolean secure,
        String sameSite,
        Duration accessTokenMaxAge,
        Duration refreshTokenMaxAge,
        Duration refreshTokenAbsoluteMaxAge,
        Duration reuseDetectionRetention,
        String domain,
        String accessTokenName,
        String refreshTokenName
) {
    public static final String HOST_ACCESS_TOKEN_NAME =
            "__Host-safeai-access";

    public static final String HOST_REFRESH_TOKEN_NAME =
            "__Host-safeai-refresh";

    public static final String HOST_CSRF_TOKEN_NAME =
            "__Host-XSRF-TOKEN";

    public static final String LEGACY_CSRF_TOKEN_NAME =
            "XSRF-TOKEN";

    private static final Duration MAX_ACCESS_TOKEN_MAX_AGE =
            Duration.ofMinutes(60);

    private static final Duration MAX_REFRESH_TOKEN_MAX_AGE =
            Duration.ofDays(90);

    private static final Duration MAX_REFRESH_TOKEN_ABSOLUTE_MAX_AGE =
            Duration.ofDays(90);

    private static final Duration MAX_REUSE_DETECTION_RETENTION =
            Duration.ofDays(30);

    private static final Pattern COOKIE_NAME_PATTERN =
            Pattern.compile(
                    "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$"
            );

    private static final Pattern IPV4_PATTERN =
            Pattern.compile(
                    "^(?:\\d{1,3}\\.){3}\\d{1,3}$"
            );

    public AuthCookieProperties {
        sameSite =
                normalizeSameSite(
                        sameSite
                );

        domain =
                normalizeDomain(
                        domain
                );

        requirePositive(
                accessTokenMaxAge,
                "safeai.auth.cookies.access-token-max-age"
        );

        requirePositive(
                refreshTokenMaxAge,
                "safeai.auth.cookies.refresh-token-max-age"
        );

        requirePositive(
                refreshTokenAbsoluteMaxAge,
                "safeai.auth.cookies.refresh-token-absolute-max-age"
        );

        requirePositive(
                reuseDetectionRetention,
                "safeai.auth.cookies.reuse-detection-retention"
        );

        if (accessTokenMaxAge.compareTo(
                MAX_ACCESS_TOKEN_MAX_AGE
        ) > 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.access-token-max-age "
                            + "не должен превышать 60 минут"
            );
        }

        if (refreshTokenMaxAge.compareTo(
                MAX_REFRESH_TOKEN_MAX_AGE
        ) > 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.refresh-token-max-age "
                            + "не должен превышать 90 дней"
            );
        }

        if (refreshTokenAbsoluteMaxAge.compareTo(
                MAX_REFRESH_TOKEN_ABSOLUTE_MAX_AGE
        ) > 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.refresh-token-absolute-max-age "
                            + "не должен превышать 90 дней"
            );
        }

        if (reuseDetectionRetention.compareTo(
                MAX_REUSE_DETECTION_RETENTION
        ) > 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.reuse-detection-retention "
                            + "не должен превышать 30 дней"
            );
        }

        if (refreshTokenMaxAge.compareTo(
                accessTokenMaxAge
        ) <= 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.refresh-token-max-age "
                            + "должен быть больше access-token-max-age"
            );
        }

        if (refreshTokenAbsoluteMaxAge.compareTo(
                refreshTokenMaxAge
        ) < 0) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.refresh-token-absolute-max-age "
                            + "не должен быть меньше refresh-token-max-age"
            );
        }

        if ("None".equals(
                sameSite
        )
                && !secure) {
            throw new IllegalStateException(
                    "SameSite=None требует Secure=true"
            );
        }

        boolean hasDomain =
                domain != null
                        && !domain.isBlank();

        accessTokenName =
                normalizeCookieName(
                        accessTokenName,
                        secure && !hasDomain
                                ? HOST_ACCESS_TOKEN_NAME
                                : "safeai-access",
                        "safeai.auth.cookies.access-token-name"
                );

        /*
         * Refresh cookie is Path=/ as well. This is deliberate: production
         * host-binding through __Host- is stronger against sibling-subdomain
         * cookie injection than the previous narrower /api/auth path.
         */
        refreshTokenName =
                normalizeCookieName(
                        refreshTokenName,
                        secure && !hasDomain
                                ? HOST_REFRESH_TOKEN_NAME
                                : (
                                secure
                                        ? "__Secure-safeai-refresh"
                                        : "safeai-refresh"
                        ),
                        "safeai.auth.cookies.refresh-token-name"
                );

        validateCookiePrefix(
                accessTokenName,
                secure,
                hasDomain,
                "access-token-name"
        );

        validateCookiePrefix(
                refreshTokenName,
                secure,
                hasDomain,
                "refresh-token-name"
        );
    }

    public boolean hasDomain() {
        return domain != null
                && !domain.isBlank();
    }

    /**
     * CookieCsrfTokenRepository cookie name.
     *
     * <p>Production host-only secure configuration receives the __Host-
     * prefix. Non-production/cross-domain compatibility retains XSRF-TOKEN,
     * but ProductionAuthCookieInvariantValidator rejects that weaker boundary
     * in production.</p>
     */
    public String csrfTokenName() {
        return secure
                && !hasDomain()
                ? HOST_CSRF_TOKEN_NAME
                : LEGACY_CSRF_TOKEN_NAME;
    }

    private static void requirePositive(
            Duration value,
            String propertyName
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()) {
            throw new IllegalStateException(
                    propertyName
                            + " должен быть положительным"
            );
        }
    }

    private static String normalizeSameSite(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            throw new IllegalStateException(
                    "safeai.auth.cookies.same-site не задан"
            );
        }

        return switch (
                value.trim()
                        .toLowerCase(
                                Locale.ROOT
                        )
        ) {
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

    private static String normalizeDomain(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        String normalized =
                value.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalized.startsWith(
                "."
        )) {
            normalized =
                    normalized.substring(
                            1
                    );
        }

        if (normalized.contains(
                "://"
        )
                || normalized.contains(
                "/"
        )
                || normalized.contains(
                ":"
        )
                || normalized.chars()
                .anyMatch(
                        Character::isWhitespace
                )) {
            throw invalidDomain(
                    value
            );
        }

        final String ascii;

        try {
            ascii =
                    IDN.toASCII(
                                    normalized,
                                    IDN.USE_STD3_ASCII_RULES
                            )
                            .toLowerCase(
                                    Locale.ROOT
                            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Недопустимое значение safeai.auth.cookies.domain: "
                            + value,
                    exception
            );
        }

        if (ascii.isBlank()
                || ascii.length() > 253
                || IPV4_PATTERN.matcher(
                ascii
        ).matches()) {
            throw invalidDomain(
                    value
            );
        }

        for (String label
                : ascii.split(
                "\\.",
                -1
        )) {
            if (label.isBlank()
                    || label.length() > 63
                    || label.startsWith(
                    "-"
            )
                    || label.endsWith(
                    "-"
            )) {
                throw invalidDomain(
                        value
                );
            }
        }

        return ascii;
    }

    private static String normalizeCookieName(
            String value,
            String defaultValue,
            String propertyName
    ) {
        String normalized =
                value == null
                        || value.isBlank()
                        ? defaultValue
                        : value.trim();

        if (!COOKIE_NAME_PATTERN.matcher(
                normalized
        ).matches()) {
            throw new IllegalStateException(
                    "Некорректное имя cookie в "
                            + propertyName
            );
        }

        return normalized;
    }

    private static void validateCookiePrefix(
            String name,
            boolean secure,
            boolean hasDomain,
            String propertyName
    ) {
        if (name.startsWith(
                "__Secure-"
        )
                && !secure) {
            throw new IllegalStateException(
                    propertyName
                            + " с префиксом __Secure- требует Secure=true"
            );
        }

        if (name.startsWith(
                "__Host-"
        )
                && (
                !secure
                        || hasDomain
        )) {
            throw new IllegalStateException(
                    propertyName
                            + " с префиксом __Host- требует "
                            + "Secure=true и отсутствие Domain; Path=/ "
                            + "фиксирован политикой auth cookie"
            );
        }
    }

    private static IllegalStateException invalidDomain(
            String value
    ) {
        return new IllegalStateException(
                "safeai.auth.cookies.domain должен быть корректным hostname "
                        + "без scheme, path, port и IP-адреса: "
                        + value
        );
    }
}
