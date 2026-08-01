package ru.safeai.gateway.ai.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "safeai.ai")
public record AiProviderProperties(
        String provider
) {
    private static final Set<String> ALLOWED_PROVIDERS =
            Set.of("mock", "openai", "anthropic");

    public AiProviderProperties {
        provider = provider == null || provider.isBlank()
                ? "mock"
                : provider.trim().toLowerCase(Locale.ROOT);

        if (!ALLOWED_PROVIDERS.contains(provider)) {
            throw new IllegalStateException(
                    "Недопустимый safeai.ai.provider: " + provider
                            + ". Допустимые значения: "
                            + ALLOWED_PROVIDERS
            );
        }
    }
}
