package ru.safeai.gateway.model.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;

/**
 * Transitional Model Control Plane read model.
 *
 * <p>The application currently installs exactly one provider bean, so a
 * catalogue endpoint must expose that limitation rather than pretend that it
 * can route among configured providers. Tenant policies and routing are added
 * only after the provider multiplexer exists.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimeModelStatusService {

    private static final String STATIC_SINGLE_PROVIDER =
            "SINGLE_PROVIDER_STATIC";

    private final AiProviderProperties providerProperties;
    private final ModelPricingProperties pricingProperties;
    private final org.springframework.beans.factory.ObjectProvider<OpenAiProperties>
            openAiProperties;
    private final org.springframework.beans.factory.ObjectProvider<AnthropicProperties>
            anthropicProperties;

    public RuntimeModelStatusResponse current() {
        return switch (providerProperties.provider()) {
            case "mock" -> response("mock", "mock-safeai", 64_000, 2_048);
            case "openai" -> fromOpenAi(requiredOpenAi());
            case "anthropic" -> fromAnthropic(requiredAnthropic());
            default -> throw new IllegalStateException("Unsupported configured AI provider");
        };
    }

    private RuntimeModelStatusResponse fromOpenAi(OpenAiProperties properties) {
        return response(
                "openai",
                properties.model(),
                properties.maxInputTokens(),
                properties.maxOutputTokens()
        );
    }

    private RuntimeModelStatusResponse fromAnthropic(AnthropicProperties properties) {
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
        ModelPricingProperties.ModelPrice price = pricingProperties.find(model);

        return new RuntimeModelStatusResponse(
                provider,
                model,
                true,
                STATIC_SINGLE_PROVIDER,
                maxInputTokens,
                maxOutputTokens,
                false,
                false,
                false,
                "NOT_DECLARED",
                "NOT_PROBED",
                price == null ? "UNPRICED" : (price.free() ? "FREE" : "CONFIGURED"),
                price == null ? null : price.inputUsdPer1mTokens(),
                price == null ? null : price.outputUsdPer1mTokens(),
                price == null ? null : price.version()
        );
    }

    private OpenAiProperties requiredOpenAi() {
        OpenAiProperties properties = openAiProperties.getIfAvailable();
        if (properties == null) {
            throw new IllegalStateException("OpenAI provider properties are unavailable");
        }
        return properties;
    }

    private AnthropicProperties requiredAnthropic() {
        AnthropicProperties properties = anthropicProperties.getIfAvailable();
        if (properties == null) {
            throw new IllegalStateException("Anthropic provider properties are unavailable");
        }
        return properties;
    }
}
