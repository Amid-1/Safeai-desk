package ru.safeai.gateway.model.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.model.config.ModelRoutingEnvelopeProperties;
import ru.safeai.gateway.model.domain.ModelCapability;

import java.util.Objects;
import java.util.Set;

@Service
@EnableConfigurationProperties(ModelRoutingEnvelopeProperties.class)
public class ModelRoutingEnvelopeService {

    private final ModelRoutingEnvelopeProperties properties;

    public ModelRoutingEnvelopeService(
            ModelRoutingEnvelopeProperties properties
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
    }

    public long additionalInputUnitUpperBound(
            boolean usesKnowledge,
            Set<ModelCapability> requiredCapabilities
    ) {
        Set<ModelCapability> capabilities =
                requiredCapabilities == null
                        ? Set.of()
                        : requiredCapabilities;

        long result =
                properties.systemAndDeveloperInputUnits();

        if (usesKnowledge) {
            result = Math.addExact(
                    result,
                    properties.ragContextInputUnits()
            );
        }

        if (capabilities.contains(
                ModelCapability.TOOLS
        )) {
            result = Math.addExact(
                    result,
                    properties.toolSchemaInputUnits()
            );
        }

        return result;
    }

    /**
     * Compatibility alias for configuration/tests using the pre-V48 name.
     */
    @Deprecated(forRemoval = false)
    public long additionalInputTokenUpperBound(
            boolean usesKnowledge,
            Set<ModelCapability> requiredCapabilities
    ) {
        return additionalInputUnitUpperBound(
                usesKnowledge,
                requiredCapabilities
        );
    }
}
