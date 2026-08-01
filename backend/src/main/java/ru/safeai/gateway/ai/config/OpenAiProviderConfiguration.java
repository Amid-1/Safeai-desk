package ru.safeai.gateway.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "safeai.ai",
        name = "provider",
        havingValue = "openai"
)
@EnableConfigurationProperties(
        OpenAiProperties.class
)
public class OpenAiProviderConfiguration {
}