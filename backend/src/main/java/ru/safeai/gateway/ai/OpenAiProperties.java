package ru.safeai.gateway.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.ai.openai")
public record OpenAiProperties(
        String baseUrl,
        String apiKey,
        String model
) {

    public OpenAiProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com/v1"
                : baseUrl;

        model = model == null || model.isBlank()
                ? "gpt-4.1"
                : model;
    }

    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY не задан");
        }

        if (model == null || model.isBlank()) {
            throw new IllegalStateException("OPENAI_MODEL не задан");
        }
    }
}