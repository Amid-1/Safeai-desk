package ru.safeai.gateway.auth.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Result produced before the refresh-rotation transaction commits.
 */
public record RefreshSessionResult(
        String accessToken,
        String rawRefreshToken,
        Duration refreshCookieMaxAge
) {
    public RefreshSessionResult {
        requireText(
                accessToken,
                "accessToken"
        );

        requireText(
                rawRefreshToken,
                "rawRefreshToken"
        );

        Objects.requireNonNull(
                refreshCookieMaxAge,
                "refreshCookieMaxAge не должен быть null"
        );

        if (refreshCookieMaxAge.isZero()
                || refreshCookieMaxAge.isNegative()) {
            throw new IllegalArgumentException(
                    "refreshCookieMaxAge должен быть положительным"
            );
        }
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public String toString() {
        return "RefreshSessionResult["
                + "accessToken=<redacted>, "
                + "rawRefreshToken=<redacted>, "
                + "refreshCookieMaxAge="
                + refreshCookieMaxAge
                + "]";
    }

    private static void requireText(
            String value,
            String fieldName
    ) {
        Objects.requireNonNull(
                value,
                fieldName
                        + " не должен быть null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName
                            + " не должен быть пустым"
            );
        }
    }
}
