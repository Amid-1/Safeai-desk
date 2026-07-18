package ru.safeai.gateway.ai.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.safeai.gateway.ai.provider.ProviderPropertyValidator;

import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "safeai.ai.openai")
public record OpenAiProperties(
        String baseUrl,
        String apiKey,
        String model,
        Integer maxOutputTokens,
        Integer maxResponseChars,
        Boolean store,
        Duration connectTimeout,
        Duration readTimeout
) {
    private static final Set<String> ALLOWED_HOSTS =
            Set.of("api.openai.com");

    public OpenAiProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com/v1"
                : baseUrl;

        baseUrl = ProviderPropertyValidator
                .requireAllowedHttpsBaseUrl(
                        baseUrl,
                        "safeai.ai.openai.base-url",
                        ALLOWED_HOSTS
                );

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY не задан"
            );
        }

        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_MODEL не задан"
            );
        }

        model = model.trim();

        maxOutputTokens =
                ProviderPropertyValidator.requireIntRange(
                        maxOutputTokens,
                        1_024,
                        1,
                        100_000,
                        "safeai.ai.openai.max-output-tokens"
                );

        maxResponseChars =
                ProviderPropertyValidator.requireIntRange(
                        maxResponseChars,
                        100_000,
                        1,
                        1_000_000,
                        "safeai.ai.openai.max-response-chars"
                );

        connectTimeout =
                ProviderPropertyValidator.requireConnectTimeout(
                        connectTimeout == null
                                ? Duration.ofSeconds(5)
                                : connectTimeout,
                        "safeai.ai.openai.connect-timeout"
                );

        readTimeout =
                ProviderPropertyValidator.requireReadTimeout(
                        readTimeout == null
                                ? Duration.ofSeconds(60)
                                : readTimeout,
                        "safeai.ai.openai.read-timeout"
                );
    }

    public boolean effectiveStore() {
        return Boolean.TRUE.equals(store);
    }
}
