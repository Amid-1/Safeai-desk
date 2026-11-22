package ru.safeai.gateway.ai.provider;

import org.jspecify.annotations.NullMarked;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Objects;

@NullMarked
public final class AiRestClientFactory {
    private static final long DEFAULT_MAX_RESPONSE_BODY_BYTES = 2L * 1024 * 1024;
    private static final long MAX_RESPONSE_BODY_BYTES = 16L * 1024 * 1024;

    private AiRestClientFactory() {}

    public static RestClient create(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        return create(baseUrl, connectTimeout, readTimeout, DEFAULT_MAX_RESPONSE_BODY_BYTES);
    }

    public static RestClient create(
            String baseUrl, Duration connectTimeout, Duration readTimeout, long maxResponseBodyBytes
    ) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        requirePositiveDuration(connectTimeout, "connectTimeout");
        requirePositiveDuration(readTimeout, "readTimeout");
        if (maxResponseBodyBytes <= 0 || maxResponseBodyBytes > MAX_RESPONSE_BODY_BYTES) {
            throw new IllegalArgumentException("maxResponseBodyBytes вне допустимого диапазона");
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new AiBoundedHttpRequestFactory(
                        connectTimeout, readTimeout, Math.toIntExact(maxResponseBodyBytes)))
                .build();
    }

    private static void requirePositiveDuration(Duration duration, String propertyName) {
        Objects.requireNonNull(duration, propertyName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " должен быть положительным");
        }
    }
}
