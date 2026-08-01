package ru.safeai.gateway.ai.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.safeai.gateway.ai.provider.ProviderPropertyValidator;

import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "safeai.ai.openai")
public final class OpenAiProperties {

    private static final String PROPERTY_PREFIX =
            "safeai.ai.openai";

    private static final String DEFAULT_BASE_URL =
            "https://api.openai.com/v1";

    private static final int MAX_MODEL_CHARS =
            100;

    private static final int DEFAULT_MAX_INPUT_TOKENS =
            64_000;

    private static final int MIN_INPUT_TOKENS =
            1_024;

    private static final int MAX_INPUT_TOKENS =
            1_000_000;

    private static final int DEFAULT_MAX_OUTPUT_TOKENS =
            2_048;

    private static final int MIN_OUTPUT_TOKENS =
            1;

    private static final int MAX_OUTPUT_TOKENS =
            8_192;

    private static final int DEFAULT_MAX_RESPONSE_CHARS =
            100_000;

    private static final int MIN_RESPONSE_CHARS =
            1;

    private static final int MAX_RESPONSE_CHARS =
            1_000_000;

    private static final long DEFAULT_MAX_RESPONSE_BODY_BYTES =
            2L * 1024L * 1024L;

    private static final long MIN_RESPONSE_BODY_BYTES =
            64L * 1024L;

    private static final long MAX_RESPONSE_BODY_BYTES =
            16L * 1024L * 1024L;

    private static final Duration DEFAULT_CONNECT_TIMEOUT =
            Duration.ofSeconds(5);

    private static final Duration DEFAULT_READ_TIMEOUT =
            Duration.ofSeconds(60);

    private static final Set<String> ALLOWED_BASE_URLS =
            Set.of(DEFAULT_BASE_URL);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxInputTokens;
    private final int maxOutputTokens;
    private final int maxResponseChars;
    private final long maxResponseBodyBytes;
    private final boolean store;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public OpenAiProperties(
            String baseUrl,
            String apiKey,
            String model,
            Integer maxInputTokens,
            Integer maxOutputTokens,
            Integer maxResponseChars,
            Long maxResponseBodyBytes,
            Boolean store,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        this.baseUrl =
                ProviderPropertyValidator.requireAllowedHttpsBaseUrl(
                        resolveBaseUrl(baseUrl),
                        propertyName("base-url"),
                        ALLOWED_BASE_URLS
                );

        this.apiKey =
                ProviderPropertyValidator.requireSecret(
                        apiKey,
                        "OPENAI_API_KEY"
                );

        this.model =
                ProviderPropertyValidator.requireString(
                        model,
                        MAX_MODEL_CHARS,
                        "OPENAI_MODEL"
                );

        this.maxInputTokens =
                ProviderPropertyValidator.requireIntRange(
                        maxInputTokens,
                        DEFAULT_MAX_INPUT_TOKENS,
                        MIN_INPUT_TOKENS,
                        MAX_INPUT_TOKENS,
                        propertyName("max-input-tokens")
                );

        this.maxOutputTokens =
                ProviderPropertyValidator.requireIntRange(
                        maxOutputTokens,
                        DEFAULT_MAX_OUTPUT_TOKENS,
                        MIN_OUTPUT_TOKENS,
                        MAX_OUTPUT_TOKENS,
                        propertyName("max-output-tokens")
                );

        this.maxResponseChars =
                ProviderPropertyValidator.requireIntRange(
                        maxResponseChars,
                        DEFAULT_MAX_RESPONSE_CHARS,
                        MIN_RESPONSE_CHARS,
                        MAX_RESPONSE_CHARS,
                        propertyName("max-response-chars")
                );

        this.maxResponseBodyBytes =
                ProviderPropertyValidator.requireLongRange(
                        maxResponseBodyBytes,
                        DEFAULT_MAX_RESPONSE_BODY_BYTES,
                        MIN_RESPONSE_BODY_BYTES,
                        MAX_RESPONSE_BODY_BYTES,
                        propertyName("max-response-body-bytes")
                );

        this.store =
                Boolean.TRUE.equals(store);

        this.connectTimeout =
                ProviderPropertyValidator.requireConnectTimeout(
                        resolveConnectTimeout(connectTimeout),
                        propertyName("connect-timeout")
                );

        this.readTimeout =
                ProviderPropertyValidator.requireReadTimeout(
                        resolveReadTimeout(readTimeout),
                        propertyName("read-timeout")
                );
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public int maxInputTokens() {
        return maxInputTokens;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public int maxResponseChars() {
        return maxResponseChars;
    }

    public long maxResponseBodyBytes() {
        return maxResponseBodyBytes;
    }

    public boolean store() {
        return store;
    }

    /**
     * Оставлено для совместимости с существующим OpenAiProvider.
     */
    public boolean effectiveStore() {
        return store;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    private static String propertyName(
            String property
    ) {
        return PROPERTY_PREFIX + "." + property;
    }

    private static String resolveBaseUrl(
            String baseUrl
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }

        return baseUrl;
    }

    private static Duration resolveConnectTimeout(
            Duration connectTimeout
    ) {
        return connectTimeout == null
                ? DEFAULT_CONNECT_TIMEOUT
                : connectTimeout;
    }

    private static Duration resolveReadTimeout(
            Duration readTimeout
    ) {
        return readTimeout == null
                ? DEFAULT_READ_TIMEOUT
                : readTimeout;
    }
}