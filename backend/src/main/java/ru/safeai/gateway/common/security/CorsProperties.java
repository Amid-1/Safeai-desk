package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NullUnmarked;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@NullUnmarked
@ConfigurationProperties(prefix = "safeai.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {
    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(CorsProperties::normalizeOrigin)
                .distinct()
                .toList();
    }

    private static String normalizeOrigin(String origin) {
        if ("*".equals(origin)) {
            throw new IllegalStateException(
                    "safeai.cors.allowed-origins не может содержать '*' "
                            + "при allowCredentials(true)"
            );
        }

        if (origin.endsWith("/")) {
            throw new IllegalStateException(
                    "CORS origin должен быть без завершающего '/': " + origin
            );
        }

        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            int port = normalizeDefaultPort(scheme, uri.getPort());

            boolean validScheme = "http".equals(scheme)
                    || "https".equals(scheme);
            boolean validPort = port == -1 || (port >= 1 && port <= 65_535);
            boolean hasForbiddenParts =
                    (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                            || uri.getRawQuery() != null
                            || uri.getRawFragment() != null
                            || uri.getUserInfo() != null;

            if (!validScheme
                    || host == null
                    || host.isBlank()
                    || !validPort
                    || hasForbiddenParts) {
                throw invalidOrigin(origin, null);
            }

            return new URI(
                    scheme,
                    null,
                    host.toLowerCase(Locale.ROOT),
                    port,
                    null,
                    null,
                    null
            ).toASCIIString();
        } catch (URISyntaxException exception) {
            throw invalidOrigin(origin, exception);
        }
    }

    private static int normalizeDefaultPort(String scheme, int port) {
        if (("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443)) {
            return -1;
        }
        return port;
    }

    private static IllegalStateException invalidOrigin(
            String origin,
            Exception cause
    ) {
        String message = "Некорректный CORS origin: " + origin;
        return cause == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, cause);
    }
}
