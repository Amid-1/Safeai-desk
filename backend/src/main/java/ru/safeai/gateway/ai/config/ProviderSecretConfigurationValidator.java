package ru.safeai.gateway.ai.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.common.security.ProductionEnvironmentCondition;

import java.nio.file.Path;
import java.util.Set;

/** Production startup must fail before serving requests if file-backed binding has drifted. */
@Component
@Conditional(ProductionEnvironmentCondition.class)
@Lazy(false)
final class ProviderSecretConfigurationValidator {
    private static final Path ROOT = Path.of("/run/secrets");
    private static final Set<String> FORBIDDEN_ENV = Set.of(
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_EMBEDDING_API_KEY", "KNOWLEDGE_OCR_API_KEY",
            "SAFEAI_AI_OPENAI_APIKEY", "SAFEAI_AI_ANTHROPIC_APIKEY",
            "SAFEAI_KNOWLEDGE_EMBEDDING_APIKEY", "SAFEAI_KNOWLEDGE_OCR_APIKEY",
            "SAFEAI_AI_OPENAI_API_KEY", "SAFEAI_AI_ANTHROPIC_API_KEY",
            "SAFEAI_KNOWLEDGE_EMBEDDING_API_KEY", "SAFEAI_KNOWLEDGE_OCR_API_KEY");
    private static final Set<String> FORBIDDEN_PROPERTIES = Set.of(
            "safeai.ai.openai.api-key", "safeai.ai.anthropic.api-key",
            "safeai.knowledge.embedding.api-key", "safeai.knowledge.ocr.api-key");

    private final ConfigurableEnvironment environment;
    private final ObjectProvider<OpenAiProperties> openai;
    private final ObjectProvider<AnthropicProperties> anthropic;
    private final AiProviderProperties provider;

    ProviderSecretConfigurationValidator(ConfigurableEnvironment environment,
                                         ObjectProvider<OpenAiProperties> openai,
                                         ObjectProvider<AnthropicProperties> anthropic,
                                         AiProviderProperties provider) {
        this.environment = environment;
        this.openai = openai;
        this.anthropic = anthropic;
        this.provider = provider;
    }

    @PostConstruct
    void validate() {
        rejectEnvironmentOverrides(environment.getSystemEnvironment().keySet());
        for (var source : environment.getPropertySources()) {
            if (Set.of("commandLineArgs", "systemProperties", "spring.application.json").contains(source.getName())
                    && (FORBIDDEN_PROPERTIES.stream().anyMatch(source::containsProperty)
                    || FORBIDDEN_ENV.stream().anyMatch(source::containsProperty))) {
                throw new IllegalStateException("Production provider credentials must not be passed as JVM or command-line arguments");
            }
        }
        OpenAiProperties openAi = openai.getIfAvailable();
        if ("openai".equals(provider.provider()) && openAi == null) {
            throw new IllegalStateException("Production OpenAI configuration unavailable");
        }
        if (openAi != null) ProviderSecretFile.requireMatches(ROOT.resolve("OPENAI_API_KEY"), openAi.apiKey());
        AnthropicProperties claude = anthropic.getIfAvailable();
        if ("anthropic".equals(provider.provider()) && claude == null) {
            throw new IllegalStateException("Production Anthropic configuration unavailable");
        }
        if (claude != null) ProviderSecretFile.requireMatches(ROOT.resolve("ANTHROPIC_API_KEY"), claude.apiKey());
    }

    static void rejectEnvironmentOverrides(Set<String> names) {
        if (names.stream().anyMatch(FORBIDDEN_ENV::contains)) {
            throw new IllegalStateException("Production provider credentials are forbidden in environment variables");
        }
    }
}
