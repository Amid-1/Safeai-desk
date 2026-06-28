package ru.safeai.gateway.ai.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.ai")
public record AiProviderProperties(
        String provider
) {
    public AiProviderProperties {
        if (provider == null || provider.isBlank()) {
            provider = "mock";
        } else {
            provider = provider.trim().toLowerCase();
        }
    }
}