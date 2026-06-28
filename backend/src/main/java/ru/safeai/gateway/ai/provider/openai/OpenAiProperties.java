package ru.safeai.gateway.ai.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.ai.openai")
public record OpenAiProperties(
        String baseUrl,
        String apiKey,
        String model,
        Integer maxOutputTokens,
        Integer maxResponseChars,
        Duration connectTimeout,
        Duration readTimeout
) {

    public OpenAiProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com/v1"
                : baseUrl;

        model = model == null || model.isBlank()
                ? "gpt-4.1"
                : model;

        maxOutputTokens = maxOutputTokens == null || maxOutputTokens <= 0
                ? 1024
                : maxOutputTokens;

        maxResponseChars = maxResponseChars == null || maxResponseChars <= 0
                ? 100_000
                : Math.min(maxResponseChars, 1_000_000);

        connectTimeout = connectTimeout == null
                ? Duration.ofSeconds(5)
                : connectTimeout;

        readTimeout = readTimeout == null
                ? Duration.ofSeconds(60)
                : readTimeout;
    }

    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY не задан");
        }
    }
}