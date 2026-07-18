package ru.safeai.gateway.ai.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

public final class ProviderPropertyValidator {

    private static final Duration MIN_CONNECT =
            Duration.ofMillis(100);
    private static final Duration MAX_CONNECT =
            Duration.ofSeconds(30);
    private static final Duration MIN_READ =
            Duration.ofSeconds(1);
    private static final Duration MAX_READ =
            Duration.ofMinutes(10);

    private ProviderPropertyValidator() {
    }

    public static String requireAllowedHttpsBaseUrl(
            String value,
            String propertyName,
            Set<String> allowedHosts
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " не задан"
            );
        }

        URI uri;

        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    propertyName + " содержит некорректный URL",
                    exception
            );
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !allowedHosts.contains(
                        uri.getHost().toLowerCase()
                )
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(
                    propertyName + " должен быть разрешённым HTTPS URL"
            );
        }

        return uri.toString().replaceAll("/+$", "");
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
        int effective = value == null
                ? defaultValue
                : value;

        if (effective < min || effective > max) {
            throw new IllegalStateException(
                    propertyName + " должен быть от "
                            + min + " до " + max
            );
        }

        return effective;
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
                    propertyName + " должен быть в диапазоне "
                            + min + "–" + max
            );
        }

        return value;
    }
}
