package ru.safeai.gateway.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.ai.anthropic")
public record AnthropicProperties(
        String baseUrl,
        String apiKey,
        String model,
        String version,
        Integer maxTokens
) {

    public AnthropicProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.anthropic.com/v1"
                : baseUrl;

        version = version == null || version.isBlank()
                ? "2023-06-01"
                : version;

        maxTokens = maxTokens == null || maxTokens <= 0
                ? 1024
                : maxTokens;
    }

    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY не задан");
        }

        if (model == null || model.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_MODEL не задан");
        }
    }
}