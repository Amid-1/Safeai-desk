package ru.safeai.gateway.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
                .distinct()
                .peek(CorsProperties::validateOrigin)
                .toList();
    }

    /**
     * Оставлен для совместимости с существующим SecurityConfig.
     */
    public List<String> allowedOriginList() {
        return allowedOrigins;
    }

    private static void validateOrigin(String origin) {
        if ("*".equals(origin)) {
            throw new IllegalStateException(
                    "safeai.cors.allowed-origins не может содержать '*' при allowCredentials(true)"
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

            boolean validScheme = "http".equals(scheme) || "https".equals(scheme);
            boolean hasAuthority = uri.getHost() != null;
            boolean hasForbiddenParts = uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getUserInfo() != null;

            if (!validScheme || !hasAuthority || hasForbiddenParts) {
                throw new IllegalStateException("Некорректный CORS origin: " + origin);
            }
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Некорректный CORS origin: " + origin, exception);
        }
    }
}
