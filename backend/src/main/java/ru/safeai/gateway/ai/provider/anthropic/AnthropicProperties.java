package ru.safeai.gateway.ai.provider.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.safeai.gateway.ai.provider.ProviderPropertyValidator;

import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "safeai.ai.anthropic")
public final class AnthropicProperties {

    private static final String PROPERTY_PREFIX =
            "safeai.ai.anthropic";

    private static final String DEFAULT_BASE_URL =
            "https://api.anthropic.com/v1";

    private static final String DEFAULT_API_VERSION =
            "2023-06-01";

    private static final int DEFAULT_MAX_INPUT_TOKENS =
            64_000;

    private static final int DEFAULT_MAX_TOKENS =
            2_048;

    private static final int DEFAULT_MAX_RESPONSE_CHARS =
            100_000;

    private static final long DEFAULT_MAX_RESPONSE_BODY_BYTES =
            2L * 1024L * 1024L;

    private static final Duration DEFAULT_CONNECT_TIMEOUT =
            Duration.ofSeconds(5);

    private static final Duration DEFAULT_READ_TIMEOUT =
            Duration.ofSeconds(60);

    private static final Set<String> ALLOWED_BASE_URLS =
            Set.of(DEFAULT_BASE_URL);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String version;
    private final int maxInputTokens;
    private final int maxTokens;
    private final int maxResponseChars;
    private final long maxResponseBodyBytes;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public AnthropicProperties(
            String baseUrl,
            String apiKey,
            String model,
            String version,
            Integer maxInputTokens,
            Integer maxTokens,
            Integer maxResponseChars,
            Long maxResponseBodyBytes,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        this.baseUrl =
                ProviderPropertyValidator.requireAllowedHttpsBaseUrl(
                        defaultIfBlank(
                                baseUrl,
                                DEFAULT_BASE_URL
                        ),
                        propertyName("base-url"),
                        ALLOWED_BASE_URLS
                );

        this.apiKey =
                ProviderPropertyValidator.requireSecret(
                        apiKey,
                        "ANTHROPIC_API_KEY"
                );

        this.model =
                ProviderPropertyValidator.requireString(
                        model,
                        100,
                        "ANTHROPIC_MODEL"
                );

        this.version =
                ProviderPropertyValidator.requireString(
                        defaultIfBlank(
                                version,
                                DEFAULT_API_VERSION
                        ),
                        50,
                        propertyName("version")
                );

        this.maxInputTokens =
                ProviderPropertyValidator.requireIntRange(
                        maxInputTokens,
                        DEFAULT_MAX_INPUT_TOKENS,
                        1_024,
                        1_000_000,
                        propertyName("max-input-tokens")
                );

        this.maxTokens =
                ProviderPropertyValidator.requireIntRange(
                        maxTokens,
                        DEFAULT_MAX_TOKENS,
                        1,
                        8_192,
                        propertyName("max-tokens")
                );

        this.maxResponseChars =
                ProviderPropertyValidator.requireIntRange(
                        maxResponseChars,
                        DEFAULT_MAX_RESPONSE_CHARS,
                        1,
                        1_000_000,
                        propertyName("max-response-chars")
                );

        this.maxResponseBodyBytes =
                ProviderPropertyValidator.requireLongRange(
                        maxResponseBodyBytes,
                        DEFAULT_MAX_RESPONSE_BODY_BYTES,
                        64L * 1024L,
                        16L * 1024L * 1024L,
                        propertyName("max-response-body-bytes")
                );

        this.connectTimeout =
                ProviderPropertyValidator.requireConnectTimeout(
                        defaultIfNull(
                                connectTimeout,
                                DEFAULT_CONNECT_TIMEOUT
                        ),
                        propertyName("connect-timeout")
                );

        this.readTimeout =
                ProviderPropertyValidator.requireReadTimeout(
                        defaultIfNull(
                                readTimeout,
                                DEFAULT_READ_TIMEOUT
                        ),
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

    public String version() {
        return version;
    }

    public int maxInputTokens() {
        return maxInputTokens;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int maxResponseChars() {
        return maxResponseChars;
    }

    public long maxResponseBodyBytes() {
        return maxResponseBodyBytes;
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

    private static String defaultIfBlank(
            String value,
            String defaultValue
    ) {
        return value == null || value.isBlank()
                ? defaultValue
                : value;
    }

    private static <T> T defaultIfNull(
            T value,
            T defaultValue
    ) {
        return value == null
                ? defaultValue
                : value;
    }
}