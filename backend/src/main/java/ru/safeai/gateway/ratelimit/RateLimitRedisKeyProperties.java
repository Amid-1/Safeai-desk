package ru.safeai.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "safeai.rate-limit.redis")
public record RateLimitRedisKeyProperties(
        String keyPrefix,
        String hmacSecret,
        String keyVersion
) {

    private static final int MIN_HMAC_SECRET_BYTES = 32;
    private static final int MAX_PREFIX_LENGTH = 128;

    private static final String DEFAULT_KEY_VERSION = "v1";

    private static final String KEY_PREFIX_PROPERTY =
            "safeai.rate-limit.redis.key-prefix";

    private static final String HMAC_SECRET_PROPERTY =
            "safeai.rate-limit.redis.hmac-secret";

    private static final String KEY_VERSION_PROPERTY =
            "safeai.rate-limit.redis.key-version";

    private static final Pattern PREFIX_PATTERN =
            Pattern.compile("[A-Za-z0-9._:-]+");

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]{1,32}");

    public RateLimitRedisKeyProperties {
        keyPrefix = normalizeKeyPrefix(keyPrefix);
        hmacSecret = requireValidHmacSecret(hmacSecret);
        keyVersion = normalizeKeyVersion(keyVersion);
    }

    private static String normalizeKeyPrefix(
            String rawPrefix
    ) {
        String normalized = requireNonBlank(
                rawPrefix,
                KEY_PREFIX_PROPERTY
        ).trim();

        normalized = removeTrailingColons(normalized);

        validateKeyPrefix(normalized);

        return normalized;
    }

    private static String removeTrailingColons(
            String value
    ) {
        int endIndex = value.length();

        while (endIndex > 0
                && value.charAt(endIndex - 1) == ':') {
            endIndex--;
        }

        return value.substring(0, endIndex);
    }

    private static void validateKeyPrefix(
            String keyPrefix
    ) {
        if (keyPrefix.isBlank()) {
            throw new IllegalStateException(
                    KEY_PREFIX_PROPERTY + " не задан"
            );
        }

        if (keyPrefix.length() > MAX_PREFIX_LENGTH) {
            throw new IllegalStateException(
                    KEY_PREFIX_PROPERTY
                            + " не должен превышать "
                            + MAX_PREFIX_LENGTH
                            + " символов"
            );
        }

        if (!PREFIX_PATTERN.matcher(keyPrefix).matches()) {
            throw new IllegalStateException(
                    KEY_PREFIX_PROPERTY
                            + " имеет недопустимый формат"
            );
        }
    }

    private static String requireValidHmacSecret(
            String hmacSecret
    ) {
        String validated = requireNonBlank(
                hmacSecret,
                HMAC_SECRET_PROPERTY
        );

        int secretBytes = validated
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (secretBytes < MIN_HMAC_SECRET_BYTES) {
            throw new IllegalStateException(
                    HMAC_SECRET_PROPERTY
                            + " должен содержать минимум "
                            + MIN_HMAC_SECRET_BYTES
                            + " байта"
            );
        }

        /*
         * Секрет намеренно не trim-ится:
         * пробелы могут являться частью криптографического ключа.
         */
        return validated;
    }

    private static String normalizeKeyVersion(
            String rawVersion
    ) {
        String normalized =
                rawVersion == null || rawVersion.isBlank()
                        ? DEFAULT_KEY_VERSION
                        : rawVersion.trim();

        if (!VERSION_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    KEY_VERSION_PROPERTY
                            + " имеет недопустимый формат"
            );
        }

        return normalized;
    }

    private static String requireNonBlank(
            String value,
            String propertyName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " не задан"
            );
        }

        return value;
    }
}