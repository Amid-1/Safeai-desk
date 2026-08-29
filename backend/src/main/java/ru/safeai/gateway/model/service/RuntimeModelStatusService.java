package ru.safeai.gateway.model.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;

import java.util.Objects;

/**
 * Transitional Model Control Plane read model.
 *
 * <p>The application currently installs exactly one provider bean. V45 adds a
 * versioned catalog, tenant policy and deterministic routing evidence, but an
 * ALLOWED route must still match this physical provider/model exactly. The
 * actual provider/model multiplexer remains a later data-plane change.</p>
 *
 * <p>This service intentionally reports conservative capability/retention/
 * health truth. Unsupported or unprobed facts are never inferred from a model
 * name.</p>
 */
@Service
public class RuntimeModelStatusService {

    private static final String STATIC_SINGLE_PROVIDER =
            "SINGLE_PROVIDER_STATIC";

    private final AiProviderProperties providerProperties;
    private final ModelPricingProperties pricingProperties;
    private final ObjectProvider<OpenAiProperties> openAiProperties;
    private final ObjectProvider<AnthropicProperties> anthropicProperties;

    public RuntimeModelStatusService(
            AiProviderProperties providerProperties,
            ModelPricingProperties pricingProperties,
            ObjectProvider<OpenAiProperties> openAiProperties,
            ObjectProvider<AnthropicProperties> anthropicProperties
    ) {
        this.providerProperties = Objects.requireNonNull(
                providerProperties,
                "providerProperties не должен быть null"
        );
        this.pricingProperties = Objects.requireNonNull(
                pricingProperties,
                "pricingProperties не должен быть null"
        );
        this.openAiProperties = Objects.requireNonNull(
                openAiProperties,
                "openAiProperties не должен быть null"
        );
        this.anthropicProperties = Objects.requireNonNull(
                anthropicProperties,
                "anthropicProperties не должен быть null"
        );
    }

    public RuntimeModelStatusResponse current() {
        return switch (providerProperties.provider()) {
            case "mock" -> response(
                    "mock",
                    "mock-safeai",
                    64_000,
                    2_048
            );
            case "openai" -> fromOpenAi(requiredOpenAi());
            case "anthropic" -> fromAnthropic(requiredAnthropic());
            default -> throw new IllegalStateException(
                    "Unsupported configured AI provider: "
                            + providerProperties.provider()
            );
        };
    }

    private RuntimeModelStatusResponse fromOpenAi(
            OpenAiProperties properties
    ) {
        return response(
                "openai",
                properties.model(),
                properties.maxInputTokens(),
                properties.maxOutputTokens()
        );
    }

    private RuntimeModelStatusResponse fromAnthropic(
            AnthropicProperties properties
    ) {
        return response(
                "anthropic",
                properties.model(),
                properties.maxInputTokens(),
                properties.maxTokens()
        );
    }

    private RuntimeModelStatusResponse response(
            String provider,
            String model,
            int maxInputTokens,
            int maxOutputTokens
    ) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "Configured runtime model не задан для provider="
                            + provider
            );
        }
        if (maxInputTokens <= 0 || maxOutputTokens <= 0) {
            throw new IllegalStateException(
                    "Configured runtime token limits должны быть положительными"
            );
        }

        String normalizedModel = model.trim();
        ModelPricingProperties.ModelPrice price =
                pricingProperties.find(normalizedModel);

        return new RuntimeModelStatusResponse(
                provider,
                normalizedModel,
                true,
                STATIC_SINGLE_PROVIDER,
                maxInputTokens,
                maxOutputTokens,
                false,
                false,
                false,
                "NOT_DECLARED",
                "NOT_PROBED",
                price == null
                        ? "UNPRICED"
                        : price.free()
                        ? "FREE"
                        : "CONFIGURED",
                price == null
                        ? null
                        : price.inputUsdPer1mTokens(),
                price == null
                        ? null
                        : price.outputUsdPer1mTokens(),
                price == null
                        ? null
                        : price.version()
        );
    }

    private OpenAiProperties requiredOpenAi() {
        OpenAiProperties properties = openAiProperties.getIfAvailable();
        if (properties == null) {
            throw new IllegalStateException(
                    "OpenAI provider properties are unavailable"
            );
        }
        return properties;
    }

    private AnthropicProperties requiredAnthropic() {
        AnthropicProperties properties = anthropicProperties.getIfAvailable();
        if (properties == null) {
            throw new IllegalStateException(
                    "Anthropic provider properties are unavailable"
            );
        }
        return properties;
    }
}
