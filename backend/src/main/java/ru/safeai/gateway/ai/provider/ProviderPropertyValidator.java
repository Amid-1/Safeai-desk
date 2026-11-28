package ru.safeai.gateway.ai.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class ProviderPropertyValidator {

    private static final Duration MIN_CONNECT =
            Duration.ofMillis(100);

    private static final Duration MAX_CONNECT =
            Duration.ofSeconds(30);

    private static final Duration MIN_READ =
            Duration.ofSeconds(1);

    private static final Duration MAX_READ =
            Duration.ofSeconds(90);

    private ProviderPropertyValidator() {
    }

    public static String requireAllowedHttpsBaseUrl(
            String value,
            String propertyName,
            Set<String> allowedBaseUrls
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " не задан"
            );
        }

        String normalized =
                normalizeBaseUrl(
                        value,
                        propertyName
                );

        Set<String> normalizedAllowed =
                allowedBaseUrls.stream()
                        .map(allowed ->
                                normalizeBaseUrl(
                                        allowed,
                                        propertyName
                                )
                        )
                        .collect(
                                Collectors.toUnmodifiableSet()
                        );

        if (!normalizedAllowed.contains(normalized)) {
            throw new IllegalStateException(
                    propertyName
                            + " должен точно совпадать "
                            + "с разрешённым HTTPS base URL"
            );
        }

        return normalized;
    }

    public static Duration requireConnectTimeout(
            Duration value,
            String propertyName
    ) {
        return requireRange(
                value,
                propertyName,
                MIN_CONNECT,
                MAX_CONNECT
        );
    }

    public static Duration requireReadTimeout(
            Duration value,
            String propertyName
    ) {
        return requireRange(
                value,
                propertyName,
                MIN_READ,
                MAX_READ
        );
    }

    public static int requireIntRange(
            Integer value,
            int defaultValue,
            int min,
            int max,
            String propertyName
    ) {
        int effective =
                value == null
                        ? defaultValue
                        : value;

        if (effective < min || effective > max) {
            throw new IllegalStateException(
                    propertyName
                            + " должен быть от "
                            + min
                            + " до "
                            + max
            );
        }

        return effective;
    }

    public static long requireLongRange(
            Long value,
            long defaultValue,
            long min,
            long max,
            String propertyName
    ) {
        long effective =
                value == null
                        ? defaultValue
                        : value;

        if (effective < min || effective > max) {
            throw new IllegalStateException(
                    propertyName
                            + " должен быть от "
                            + min
                            + " до "
                            + max
            );
        }

        return effective;
    }

    public static String requireSecret(
            String value,
            String propertyName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " не задан"
            );
        }

        String normalized =
                value.trim();

        if (normalized.length() > 4_096
                || !normalized.matches("[A-Za-z0-9._=+/~-]+")) {
            throw new IllegalStateException(
                    propertyName
                            + " имеет недопустимый формат"
            );
        }

        return normalized;
    }

    public static String requireString(
            String value,
            int maxLength,
            String propertyName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " не задан"
            );
        }

        String normalized =
                value.trim();

        if (normalized.length() > maxLength) {
            throw new IllegalStateException(
                    propertyName
                            + " превышает "
                            + maxLength
                            + " символов"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException(propertyName + " содержит управляющие символы");
        }

        return normalized;
    }

    private static String normalizeBaseUrl(
            String value,
            String propertyName
    ) {
        URI uri;

        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException ignored) {
            throw new IllegalStateException(
                    propertyName
                            + " содержит некорректный URL"
            );
        }

        boolean invalidUrl =
                !"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null
                        || uri.getFragment() != null
                        || uri.getQuery() != null
                        || hasCustomPort(uri)
                        || !uri.getRawPath().matches("/?[A-Za-z0-9/_-]*")
                        || !uri.getRawPath().equals(uri.normalize().getRawPath())
                        || !validAuthority(uri);

        if (invalidUrl) {
            throw new IllegalStateException(
                    propertyName
                            + " должен быть HTTPS URL "
                            + "без user-info, query, fragment "
                            + "и custom port"
            );
        }

        String host =
                uri.getHost()
                        .toLowerCase(Locale.ROOT);

        String path =
                normalizePath(
                        uri.getRawPath()
                );

        return "https://" + host + path;
    }

    private static boolean validAuthority(URI uri) {
        String expected = uri.getHost() + (uri.getPort() == -1 ? "" : ":443");
        return expected.equalsIgnoreCase(uri.getRawAuthority());
    }

    private static boolean hasCustomPort(
            URI uri
    ) {
        int port =
                uri.getPort();

        return port != -1 && port != 443;
    }

    private static String normalizePath(
            String path
    ) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        return path.replaceAll(
                "/+$",
                ""
        );
    }

    private static Duration requireRange(
            Duration value,
            String propertyName,
            Duration min,
            Duration max
    ) {
        if (value == null
                || value.compareTo(min) < 0
                || value.compareTo(max) > 0) {
            throw new IllegalStateException(
                    propertyName
                            + " должен быть в диапазоне "
                            + min
                            + "–"
                            + max
            );
        }

        return value;
    }
}
