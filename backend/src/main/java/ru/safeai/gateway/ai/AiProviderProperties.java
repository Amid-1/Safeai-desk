package ru.safeai.gateway.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "safeai.ai")
public record AiProviderProperties(
        String provider
) {
    public AiProviderProperties {
        if (provider == null || provider.isBlank()) {
            provider = "mock";
        }
    }
}