package ru.safeai.gateway.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "safeai.ai.openai")
public record OpenAiProperties(
        String baseUrl,
        String apiKey,
        String model,
        Integer maxOutputTokens,
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