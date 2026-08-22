package ru.safeai.gateway.ai.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.provider.AiProviderProperties;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Component
public final class AiProductionConfigurationValidator {

    private static final Set<String> ALLOWED_PRODUCTION_PROVIDERS =
            Set.of("openai", "anthropic");

    private final AiProviderProperties properties;
    private final Environment environment;

    public AiProductionConfigurationValidator(
            AiProviderProperties properties,
            Environment environment
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        this.environment = Objects.requireNonNull(
                environment,
                "environment не должен быть null"
        );
    }

    @PostConstruct
    void validate() {
        if (!environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            return;
        }

        String provider = normalizeProvider(
                properties.provider()
        );

        if (!ALLOWED_PRODUCTION_PROVIDERS.contains(provider)) {
            throw new IllegalStateException(
                    "В production параметр safeai.ai.provider "
                            + "должен иметь значение openai или anthropic. "
                            + "Текущее значение: "
                            + displayValue(provider)
            );
        }
    }

    private static String normalizeProvider(
            String provider
    ) {
        if (provider == null) {
            return "";
        }

        return provider
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String displayValue(
            String provider
    ) {
        return provider.isEmpty()
                ? "<не задано>"
                : provider;
    }
}
