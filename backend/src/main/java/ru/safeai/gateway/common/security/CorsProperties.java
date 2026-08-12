package ru.safeai.gateway.common.security;

import org.jspecify.annotations.NullUnmarked;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exact-origin CORS allowlist.
 *
 * <p>Wildcards, path, query, fragment и user-info запрещены.</p>
 *
 * <p>Так как browser authentication использует credentials,
 * каждый элемент allowlist должен представлять один конкретный
 * HTTP(S) origin.</p>
 */
@NullUnmarked
@ConfigurationProperties(prefix = "safeai.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {

    public CorsProperties(
            List<String> allowedOrigins
    ) {
        this.allowedOrigins =
                normalizeAllowedOrigins(
                        allowedOrigins
                );
    }

    private static List<String> normalizeAllowedOrigins(
            List<String> allowedOrigins
    ) {
        if (allowedOrigins == null
                || allowedOrigins.isEmpty()) {

            return List.of();
        }

        Set<String> normalized =
                new LinkedHashSet<>();

        for (String origin : allowedOrigins) {
            if (origin == null
                    || origin.isBlank()) {
                continue;
            }

            normalized.add(
                    normalizeOrigin(
                            origin.trim()
                    )
            );
        }

        if (normalized.isEmpty()) {
            return List.of();
        }

        return List.copyOf(
                normalized
        );
    }

    private static String normalizeOrigin(
            String origin
    ) {
        if ("*".equals(origin)) {
            throw invalidOrigin(
                    origin
            );
        }

        try {
            URI uri =
                    new URI(origin);

            validateOriginStructure(
                    uri,
                    origin
            );

            String scheme =
                    uri.getScheme()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            int port =
                    normalizePort(
                            scheme,
                            uri.getPort()
                    );

            String host =
                    uri.getHost()
                            .toLowerCase(
                                    Locale.ROOT
                            );

            URI normalized =
                    new URI(
                            scheme,
                            null,
                            host,
                            port,
                            null,
                            null,
                            null
                    );

            return normalized
                    .toASCIIString();

        } catch (
                URISyntaxException
                | IllegalArgumentException exception
        ) {
            throw invalidOrigin(
                    origin,
                    exception
            );
        }
    }

    private static void validateOriginStructure(
            URI uri,
            String originalOrigin
    ) {
        String scheme =
                uri.getScheme();

        if (scheme == null) {
            throw invalidOrigin(
                    originalOrigin
            );
        }

        String normalizedScheme =
                scheme.toLowerCase(
                        Locale.ROOT
                );

        if (!"http".equals(normalizedScheme)
                && !"https".equals(normalizedScheme)) {

            throw invalidOrigin(
                    originalOrigin
            );
        }

        if (uri.isOpaque()
                || uri.getRawAuthority() == null
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {

            throw invalidOrigin(
                    originalOrigin
            );
        }

        String path =
                uri.getRawPath();

        /*
         * Exact CORS Origin не содержит path.
         *
         * Поэтому:
         *
         * https://example.com
         *
         * разрешён, а:
         *
         * https://example.com/
         * https://example.com/login
         *
         * отклоняются.
         */
        if (path != null
                && !path.isEmpty()) {

            throw invalidOrigin(
                    originalOrigin
            );
        }

        int port =
                uri.getPort();

        if (port == 0
                || port > 65535) {

            throw invalidOrigin(
                    originalOrigin
            );
        }
    }

    private static int normalizePort(
            String scheme,
            int port
    ) {
        if ("http".equals(scheme)
                && port == 80) {

            return -1;
        }

        if ("https".equals(scheme)
                && port == 443) {

            return -1;
        }

        return port;
    }

    private static IllegalStateException invalidOrigin(
            String origin
    ) {
        return new IllegalStateException(
                "Некорректный CORS origin: "
                        + origin
        );
    }

    private static IllegalStateException invalidOrigin(
            String origin,
            Exception cause
    ) {
        return new IllegalStateException(
                "Некорректный CORS origin: "
                        + origin,
                cause
        );
    }
}