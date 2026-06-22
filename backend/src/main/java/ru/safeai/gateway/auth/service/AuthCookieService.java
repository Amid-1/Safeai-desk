package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    public void addAccessTokenCookie(HttpServletResponse response, String token) {
        addCookie(response, ACCESS_TOKEN_COOKIE, token, 15 * 60);
    }

    public void addRefreshTokenCookie(HttpServletResponse response, String token) {
        addCookie(response, REFRESH_TOKEN_COOKIE, token, 30 * 24 * 60 * 60);
    }

    public void clearAuthCookies(HttpServletResponse response) {
        addCookie(response, ACCESS_TOKEN_COOKIE, "", 0);
        addCookie(response, REFRESH_TOKEN_COOKIE, "", 0);
    }

    private void addCookie(
            HttpServletResponse response,
            String name,
            String value,
            long maxAgeSeconds
    ) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}