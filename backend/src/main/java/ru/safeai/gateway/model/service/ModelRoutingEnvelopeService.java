package ru.safeai.gateway.model.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.config.ModelRoutingEnvelopeProperties;
import ru.safeai.gateway.model.domain.ModelCapability;

import java.util.Objects;
import java.util.Set;

@Service
@EnableConfigurationProperties(ModelRoutingEnvelopeProperties.class)
public class ModelRoutingEnvelopeService {

    private final ModelRoutingEnvelopeProperties properties;

    public ModelRoutingEnvelopeService(ModelRoutingEnvelopeProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties не должен быть null");
    }

    public long additionalInputTokenUpperBound(
            KnowledgeMode knowledgeMode,
            Set<ModelCapability> requiredCapabilities
    ) {
        KnowledgeMode effectiveMode = knowledgeMode == null
                ? KnowledgeMode.GENERAL
                : knowledgeMode;
        Set<ModelCapability> capabilities = requiredCapabilities == null
                ? Set.of()
                : requiredCapabilities;

        long result = properties.systemAndDeveloperTokens();
        if (effectiveMode.usesKnowledge()) {
            result = Math.addExact(result, properties.ragContextTokens());
        }
        if (capabilities.contains(ModelCapability.TOOLS)) {
            result = Math.addExact(result, properties.toolSchemaTokens());
        }
        return result;
    }
}
