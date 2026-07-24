package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthCookieService {

    private static final String ACCESS_TOKEN_PATH = "/";
    private static final String REFRESH_TOKEN_PATH = "/api/auth";

    private static final String LEGACY_ACCESS_TOKEN_COOKIE =
            "access_token";

    private static final String LEGACY_REFRESH_TOKEN_COOKIE =
            "refresh_token";

    private final AuthCookieProperties properties;

    public void addAccessTokenCookie(
            HttpServletResponse response,
            String token
    ) {
        addHttpOnlyCookie(
                response,
                properties.accessTokenName(),
                token,
                ACCESS_TOKEN_PATH,
                properties.accessTokenMaxAge()
        );
    }

    public void addRefreshTokenCookie(
            HttpServletResponse response,
            String token,
            Duration maxAge
    ) {
        addHttpOnlyCookie(
                response,
                properties.refreshTokenName(),
                token,
                REFRESH_TOKEN_PATH,
                maxAge
        );
    }

    public String extractAccessToken(
            HttpServletRequest request
    ) {
        return extractCookie(
                request,
                properties.accessTokenName()
        );
    }

    public String extractRefreshToken(
            HttpServletRequest request
    ) {
        return extractCookie(
                request,
                properties.refreshTokenName()
        );
    }

    /**
     * Очищает только authentication cookies.
     *
     * <p>CSRF cookie управляется исключительно через
     * {@code CsrfTokenRepository}, чтобы настройки path,
     * domain, Secure и SameSite не расходились.</p>
     */
    public void clearAuthCookies(
            HttpServletResponse response
    ) {
        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

        clearCookie(
                response,
                properties.accessTokenName(),
                ACCESS_TOKEN_PATH
        );

        clearCookie(
                response,
                properties.refreshTokenName(),
                REFRESH_TOKEN_PATH
        );

        /*
         * Временная совместимость при переходе со старых
         * имён и путей cookies.
         *
         * После завершения миграционного периода эти три
         * вызова можно удалить.
         */
        clearCookie(
                response,
                LEGACY_ACCESS_TOKEN_COOKIE,
                ACCESS_TOKEN_PATH
        );

        clearCookie(
                response,
                LEGACY_REFRESH_TOKEN_COOKIE,
                ACCESS_TOKEN_PATH
        );

        clearCookie(
                response,
                LEGACY_REFRESH_TOKEN_COOKIE,
                REFRESH_TOKEN_PATH
        );
    }

    private String extractCookie(
            HttpServletRequest request,
            String cookieName
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                cookieName,
                "cookieName не должен быть null"
        );

        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (!cookieName.equals(cookie.getName())) {
                continue;
            }

            String value = cookie.getValue();

            return value == null || value.isBlank()
                    ? null
                    : value;
        }

        return null;
    }

    private void addHttpOnlyCookie(
            HttpServletResponse response,
            String name,
            String value,
            String path,
            Duration maxAge
    ) {
        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

        requireCookieName(name);
        requireCookiePath(path);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "cookie value не должен быть пустым: "
                            + name
            );
        }

        if (maxAge == null
                || maxAge.isZero()
                || maxAge.isNegative()) {
            throw new IllegalArgumentException(
                    "cookie maxAge должен быть положительным: "
                            + name
            );
        }

        ResponseCookie.ResponseCookieBuilder builder =
                ResponseCookie.from(name, value)
                        .httpOnly(true)
                        .secure(properties.secure())
                        .sameSite(properties.sameSite())
                        .path(path)
                        .maxAge(maxAge);

        if (properties.hasDomain()) {
            builder.domain(properties.domain());
        }

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                builder.build().toString()
        );
    }

    private void clearCookie(
            HttpServletResponse response,
            String name,
            String path
    ) {
        Objects.requireNonNull(
                response,
                "response не должен быть null"
        );

        requireCookieName(name);
        requireCookiePath(path);

        ResponseCookie.ResponseCookieBuilder builder =
                ResponseCookie.from(name, "")
                        .httpOnly(true)
                        .secure(properties.secure())
                        .sameSite(properties.sameSite())
                        .path(path)
                        .maxAge(Duration.ZERO);

        if (properties.hasDomain()) {
            builder.domain(properties.domain());
        }

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                builder.build().toString()
        );
    }

    private static void requireCookieName(
            String name
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "cookie name не должен быть пустым"
            );
        }
    }

    private static void requireCookiePath(
            String path
    ) {
        if (path == null
                || path.isBlank()
                || !path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "cookie path должен начинаться с '/': "
                            + path
            );
        }
    }
}

