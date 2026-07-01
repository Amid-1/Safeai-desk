package ru.safeai.gateway.auth.service;

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

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    public static final String CSRF_TOKEN_COOKIE = "XSRF-TOKEN";

    private static final String COOKIE_PATH = "/";

    private final AuthCookieProperties properties;

    public void addAccessTokenCookie(HttpServletResponse response, String token) {
        addHttpOnlyCookie(
                response,
                ACCESS_TOKEN_COOKIE,
                token,
                properties.accessTokenMaxAge()
        );
    }

    public void addRefreshTokenCookie(HttpServletResponse response, String token) {
        addHttpOnlyCookie(
                response,
                REFRESH_TOKEN_COOKIE,
                token,
                properties.refreshTokenMaxAge()
        );
    }

    public String extractRefreshToken(HttpServletRequest request) {
        Objects.requireNonNull(request, "request не должен быть null");

        if (request.getCookies() == null) {
            return null;
        }

        for (var cookie : request.getCookies()) {
            if (REFRESH_TOKEN_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }

        return null;
    }

    public void clearAuthCookies(HttpServletResponse response) {
        clearCookie(response, ACCESS_TOKEN_COOKIE, true);
        clearCookie(response, REFRESH_TOKEN_COOKIE, true);
        clearCookie(response, CSRF_TOKEN_COOKIE, false);
    }

    private void addHttpOnlyCookie(
            HttpServletResponse response,
            String name,
            String value,
            Duration maxAge
    ) {
        Objects.requireNonNull(response, "response не должен быть null");

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("cookie value не должен быть пустым: " + name);
        }

        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(COOKIE_PATH)
                .maxAge(maxAge);

        if (properties.hasDomain()) {
            builder.domain(properties.domain());
        }

        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private void clearCookie(
            HttpServletResponse response,
            String name,
            boolean httpOnly
    ) {
        Objects.requireNonNull(response, "response не должен быть null");

        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, "")
                .httpOnly(httpOnly)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO);

        if (properties.hasDomain()) {
            builder.domain(properties.domain());
        }

        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }
}