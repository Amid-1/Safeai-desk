package ru.safeai.gateway.ai.provider.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.safeai.gateway.ai.provider.ProviderPropertyValidator;

import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "safeai.ai.anthropic")
public record AnthropicProperties(
        String baseUrl,
        String apiKey,
        String model,
        String version,
        Integer maxTokens,
        Integer maxResponseChars,
        Duration connectTimeout,
        Duration readTimeout
) {
    private static final Set<String> ALLOWED_HOSTS =
            Set.of("api.anthropic.com");

    public AnthropicProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.anthropic.com/v1"
                : baseUrl;

        baseUrl = ProviderPropertyValidator
                .requireAllowedHttpsBaseUrl(
                        baseUrl,
                        "safeai.ai.anthropic.base-url",
                        ALLOWED_HOSTS
                );

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY не задан"
            );
        }

        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_MODEL не задан"
            );
        }

        model = model.trim();

        version = version == null || version.isBlank()
                ? "2023-06-01"
                : version.trim();

        maxTokens = ProviderPropertyValidator.requireIntRange(
                maxTokens,
                1_024,
                1,
                200_000,
                "safeai.ai.anthropic.max-tokens"
        );

        maxResponseChars =
                ProviderPropertyValidator.requireIntRange(
                        maxResponseChars,
                        100_000,
                        1,
                        1_000_000,
                        "safeai.ai.anthropic.max-response-chars"
                );

        connectTimeout =
                ProviderPropertyValidator.requireConnectTimeout(
                        connectTimeout == null
                                ? Duration.ofSeconds(5)
                                : connectTimeout,
                        "safeai.ai.anthropic.connect-timeout"
                );

        readTimeout =
                ProviderPropertyValidator.requireReadTimeout(
                        readTimeout == null
                                ? Duration.ofSeconds(60)
                                : readTimeout,
                        "safeai.ai.anthropic.read-timeout"
                );
    }
}
