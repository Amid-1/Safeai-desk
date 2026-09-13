package ru.safeai.gateway.ai.pricing;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;

import java.util.Objects;

/** Transitional adapter until VersionedPriceBookPricingResolver is enabled. */
@Service
public class StaticConfigurationPricingResolver implements PricingResolver {
    private final ModelPricingService pricing;
    public StaticConfigurationPricingResolver(ModelPricingService pricing) {
        this.pricing = Objects.requireNonNull(pricing, "pricing не должен быть null");
    }
    @Override
    public PricingResult resolve(ProviderExecutionTarget target, String resolvedPhysicalModel, AiTokenUsage usage) {
        Objects.requireNonNull(target, "target не должен быть null");
        return pricing.calculate(resolvedPhysicalModel, usage);
    }
}
