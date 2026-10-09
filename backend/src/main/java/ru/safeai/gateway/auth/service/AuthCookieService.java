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
 * Owns authentication cookies only. CSRF cookies are managed exclusively by
 * CsrfTokenRepository. Expiration identities are explicit: name, path and
 * optional Domain attribute. Historical names must be verified against the
 * application's deployment history before adding new entries.
 */
@Service
public class AuthCookieService {

    private static final String ACCESS_PATH = "/";
    private static final String REFRESH_PATH = "/api/auth";

    private final AuthCookieProperties properties;

    public AuthCookieService(AuthCookieProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties не должен быть null");
    }

    public void addAccessTokenCookie(HttpServletResponse response, String token) {
        issue(response, properties.accessTokenName(), token,
                ACCESS_PATH, properties.accessTokenMaxAge());
    }

    public void addRefreshTokenCookie(HttpServletResponse response, String token,
                                      Duration maxAge) {
        issue(response, properties.refreshTokenName(), token, REFRESH_PATH, maxAge);
    }

    public String extractAccessToken(HttpServletRequest request) {
        return extract(request, properties.accessTokenName());
    }

    public String extractRefreshToken(HttpServletRequest request) {
        return extract(request, properties.refreshTokenName());
    }

    public void clearAuthCookies(HttpServletResponse response) {
        Objects.requireNonNull(response, "response не должен быть null");
        String sameSite = validateSameSite(properties.sameSite());
        boolean secure = properties.secure();
        String domain = properties.hasDomain() ? properties.domain() : null;

        // Current identities. Domain and host-only variants are not
        // interchangeable: emit both if a Domain scope is configured.
        Set<CookieIdentity> expired = new LinkedHashSet<>();
        addScope(expired, properties.accessTokenName(), ACCESS_PATH, domain);
        addScope(expired, properties.refreshTokenName(), REFRESH_PATH, domain);
        // Исторический root-path для того же настроенного имени (если допустим).
        if (!properties.refreshTokenName().startsWith("__Host-")) {
            addScope(expired, properties.refreshTokenName(), ACCESS_PATH, domain);
        }

        // Confirmed historical paths/names from the prior implementation.
        // Names containing __Host- must never be emitted with Domain or a
        // non-root path: those are invalid browser cookie identities.
        addScope(expired, "access_token", ACCESS_PATH, domain);
        addScope(expired, "refresh_token", ACCESS_PATH, domain);
        addScope(expired, "refresh_token", REFRESH_PATH, domain);
        addScope(expired, "safeai-access", ACCESS_PATH, domain);
        addScope(expired, "safeai-refresh", ACCESS_PATH, domain);
        addScope(expired, "safeai-refresh", REFRESH_PATH, domain);
        addScope(expired, "__Host-safeai-access", ACCESS_PATH, null);
        addScope(expired, "__Secure-safeai-refresh", ACCESS_PATH, domain);
        addScope(expired, "__Secure-safeai-refresh", REFRESH_PATH, domain);

        // Historical configuration used __Host- refresh on Path=/; this is
        // the only valid path for that prefix. Never try to expire it at
        // /api/auth, because browsers reject that Set-Cookie header.
        addScope(expired, "__Host-safeai-refresh", ACCESS_PATH, null);

        // Preflight every identity before writing a single Set-Cookie header.
        // Misconfigured names must not produce a partial logout response.
        for (CookieIdentity identity : expired) {
            boolean deleteSecure = secure || prefixed(identity.name());
            validatePolicy(identity.name(), identity.path(),
                    identity.domain(), deleteSecure, sameSite);
        }

        for (CookieIdentity identity : expired) {
            boolean deleteSecure = secure || prefixed(identity.name());
            write(response, identity.name(), "", identity.path(),
                    identity.domain(), Duration.ZERO, deleteSecure, sameSite);
        }
    }

    private static void addScope(Set<CookieIdentity> identities,
                                 String name, String path, String domain) {
        requireName(name);
        identities.add(new CookieIdentity(name, path, null));
        if (domain != null && !name.startsWith("__Host-")) {
            identities.add(new CookieIdentity(name, path, domain));
        }
    }

    private String extract(HttpServletRequest request, String name) {
        Objects.requireNonNull(request, "request не должен быть null");
        requireName(name);
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie == null || !name.equals(cookie.getName())) {
                continue;
            }
            String value = cookie.getValue();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void issue(HttpServletResponse response, String name, String value,
                       String path, Duration maxAge) {
        Objects.requireNonNull(response, "response не должен быть null");
        requireName(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("cookie value не должен быть пустым: " + name);
        }
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("cookie maxAge должен быть положительным: " + name);
        }
        boolean secure = properties.secure();
        String domain = properties.hasDomain() ? properties.domain() : null;
        String sameSite = validateSameSite(properties.sameSite());
        validatePolicy(name, path, domain, secure, sameSite);
        write(response, name, value, path, domain, maxAge, secure, sameSite);
    }

    private static void write(HttpServletResponse response, String name, String value,
                              String path, String domain, Duration age,
                              boolean secure, String sameSite) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(path)
                .maxAge(age);
        if (domain != null) {
            cookie.domain(domain);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    private static String validateSameSite(String value) {
        if (value == null) {
            throw new IllegalStateException("SameSite не задан");
        }
        if ("Lax".equalsIgnoreCase(value)) return "Lax";
        if ("Strict".equalsIgnoreCase(value)) return "Strict";
        if ("None".equalsIgnoreCase(value)) return "None";
        throw new IllegalStateException("Недопустимый SameSite: " + value);
    }

    private static void validatePolicy(String name, String path,
                                       String domain, boolean secure, String sameSite) {
        requireName(name);
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("cookie path должен начинаться с '/'");
        }
        if (prefixed(name) && !secure) {
            throw new IllegalStateException(name + " требует Secure=true");
        }
        if (name.startsWith("__Host-") && (!ACCESS_PATH.equals(path) || domain != null)) {
            throw new IllegalStateException(name + " требует Path=/ и отсутствие Domain");
        }
        if ("None".equals(sameSite) && !secure) {
            throw new IllegalStateException("SameSite=None требует Secure=true");
        }
    }

    private static boolean prefixed(String name) {
        return name.startsWith("__Secure-") || name.startsWith("__Host-");
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("cookie name не должен быть пустым");
        }
    }

    private record CookieIdentity(String name, String path, String domain) { }
}
