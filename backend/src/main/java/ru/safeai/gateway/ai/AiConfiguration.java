package ru.safeai.gateway.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AiProviderProperties.class,
        OpenAiProperties.class,
        AnthropicProperties.class
})
public class AiConfiguration {
}