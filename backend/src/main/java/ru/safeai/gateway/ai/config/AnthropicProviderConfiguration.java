package ru.safeai.gateway.ai.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import ru.safeai.gateway.ai.provider.AiContextWindowService;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiResponseMetadataService;
import ru.safeai.gateway.ai.provider.AiRestClientFactory;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProvider;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "safeai.ai",
        name = "provider",
        havingValue = "anthropic"
)
@EnableConfigurationProperties(
        AnthropicProperties.class
)
public class AnthropicProviderConfiguration {

    private static final String REST_CLIENT_BEAN_NAME =
            "anthropicRestClient";

    @Bean(name = REST_CLIENT_BEAN_NAME)
    RestClient anthropicRestClient(
            AnthropicProperties properties
    ) {
        return AiRestClientFactory.create(
                properties.baseUrl(),
                properties.connectTimeout(),
                properties.readTimeout(),
                properties.maxResponseBodyBytes()
        );
    }

    @Bean
    AnthropicProvider anthropicProvider(
            AnthropicProperties properties,
            AiResponseMetadataService responseMetadataService,
            AiProviderRetryExecutor retryExecutor,
            AiContextWindowService contextWindowService,
            Clock clock,
            @Qualifier(REST_CLIENT_BEAN_NAME)
            RestClient restClient
    ) {
        return new AnthropicProvider(
                properties,
                responseMetadataService,
                retryExecutor,
                contextWindowService,
                clock,
                restClient
        );
    }
}