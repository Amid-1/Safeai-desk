package ru.safeai.gateway.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.AiRetryProperties;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;

@Configuration
@EnableConfigurationProperties({
        AiProviderProperties.class,
        OpenAiProperties.class,
        AnthropicProperties.class,
        AiRetryProperties.class,
        ModelPricingProperties.class
})
public class AiConfiguration {
}