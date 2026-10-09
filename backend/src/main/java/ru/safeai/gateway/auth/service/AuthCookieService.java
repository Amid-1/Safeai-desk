package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Issues scoped authentication cookies and expires current and legacy identities.
 * CSRF cookies are owned exclusively by CsrfTokenRepository.
 */
@Service
public class AuthCookieService {

    private static final String ACCESS_TOKEN_PATH = "/";
    private static final String REFRESH_TOKEN_PATH = "/api/auth";
    private static final String LEGACY_ACCESS_TOKEN_COOKIE = "access_token";
    private static final String LEGACY_REFRESH_TOKEN_COOKIE = "refresh_token";

    private final AuthCookieProperties properties;

    public AuthCookieService(AuthCookieProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties не должен быть null");
    }

    public void addAccessTokenCookie(HttpServletResponse response, String token) {
        addHttpOnlyCookie(response, properties.accessTokenName(), token,
                ACCESS_TOKEN_PATH, properties.accessTokenMaxAge());
    }

    public void addRefreshTokenCookie(HttpServletResponse response, String token,
                                      Duration maxAge) {
        addHttpOnlyCookie(response, properties.refreshTokenName(), token,
                REFRESH_TOKEN_PATH, maxAge);
    }

    public String extractAccessToken(HttpServletRequest request) {
        return extractCookie(request, properties.accessTokenName());
    }

    public String extractRefreshToken(HttpServletRequest request) {
        return extractCookie(request, properties.refreshTokenName());
    }

    /**
     * Browser cookie identity includes name, path and domain scope.
     * Expire historical variants without clearing the CSRF cookie.
     */
    public void clearAuthCookies(HttpServletResponse response) {
        Objects.requireNonNull(response, "response не должен быть null");

        for (CookieIdentity identity : cookiesToExpire()) {
            boolean secure = properties.secure() || hasSecurePrefix(identity.name());
            boolean hostOnly = identity.name().startsWith("__Host-");
            clearCookie(response, identity.name(), identity.path(), secure, hostOnly);
        }
    }

    private Set<CookieIdentity> cookiesToExpire() {
        // Preserve ordering while preventing duplicate name/path identities.
        Set<CookieIdentity> identities = new LinkedHashSet<>();
        identities.add(new CookieIdentity(properties.accessTokenName(), ACCESS_TOKEN_PATH));
        identities.add(new CookieIdentity(properties.refreshTokenName(), REFRESH_TOKEN_PATH));
        identities.add(new CookieIdentity(properties.refreshTokenName(), ACCESS_TOKEN_PATH));
        identities.add(new CookieIdentity("safeai-access", ACCESS_TOKEN_PATH));
        identities.add(new CookieIdentity("safeai-refresh", ACCESS_TOKEN_PATH));
        identities.add(new CookieIdentity("safeai-refresh", REFRESH_TOKEN_PATH));
        identities.add(new CookieIdentity("__Host-safeai-access", ACCESS_TOKEN_PATH));
        identities.add(new CookieIdentity("__Secure-safeai-refresh", ACCESS_TOKEN_PATH));
        identities.add(new CookieIdentity("__Secure-safeai-refresh", REFRESH_TOKEN_PATH));
        identities.add(new CookieIdentity(LEGACY_ACCESS_TOKEN_COOKIE, ACCESS_TOKEN_PATH));
        identities.add(new CookieIdentity(LEGACY_REFRESH_TOKEN_COOKIE, ACCESS_TOKEN_PATH));
        identities.add(new CookieIdentity(LEGACY_REFRESH_TOKEN_COOKIE, REFRESH_TOKEN_PATH));
        return identities;
    }

    private String extractCookie(HttpServletRequest request, String cookieName) {
        Objects.requireNonNull(request, "request не должен быть null");
        requireCookieName(cookieName);
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie == null || !cookieName.equals(cookie.getName())) {
                continue;
            }
            String value = cookie.getValue();
            return value == null || value.isBlank() ? null : value;
        }
        return null;
    }

    private void addHttpOnlyCookie(HttpServletResponse response, String name,
                                   String value, String path, Duration maxAge) {
        Objects.requireNonNull(response, "response не должен быть null");
        requireCookieName(name);
        requireCookiePath(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("cookie value не должен быть пустым: " + name);
        }
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("cookie maxAge должен быть положительным: " + name);
        }

        boolean secure = properties.secure();
        boolean hasDomain = properties.hasDomain();
        enforcePrefixPolicy(name, path, secure, hasDomain);
        String sameSite = Objects.requireNonNull(properties.sameSite(), "sameSite");
        if ("None".equalsIgnoreCase(sameSite) && !secure) {
            throw new IllegalStateException("SameSite=None требует Secure=true");
        }

        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(path)
                .maxAge(maxAge);
        if (hasDomain) {
            builder.domain(properties.domain());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private void clearCookie(HttpServletResponse response, String name,
                             String path, boolean secure, boolean hostOnly) {
        requireCookieName(name);
        requireCookiePath(path);
        String sameSite = Objects.requireNonNull(properties.sameSite(), "sameSite");
        // The __Host- deletion must never carry Domain, and prefixed deletions
        // must always have Secure even when development uses plain HTTP.
        enforcePrefixPolicy(name, path, secure, !hostOnly && properties.hasDomain());
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(path)
                .maxAge(Duration.ZERO);
        if (!hostOnly && properties.hasDomain()) {
            builder.domain(properties.domain());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private static boolean hasSecurePrefix(String name) {
        return name.startsWith("__Secure-") || name.startsWith("__Host-");
    }

    private static void enforcePrefixPolicy(String name, String path,
                                            boolean secure, boolean hasDomain) {
        if (hasSecurePrefix(name) && !secure) {
            throw new IllegalStateException(name + " требует Secure=true");
        }
        if (name.startsWith("__Host-") && (hasDomain || !"/".equals(path))) {
            throw new IllegalStateException(name + " требует Path=/ и отсутствие Domain");
        }
    }

    private static void requireCookieName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("cookie name не должен быть пустым");
        }
    }

    private static void requireCookiePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("cookie path должен начинаться с '/': " + path);
        }
    }

    private record CookieIdentity(String name, String path) {
    }
}
