package ru.safeai.gateway.ai.pricing;

import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;

/** Resolves price from an immutable execution target, not a bare model name. */
public interface PricingResolver {
    PricingResult resolve(
            ProviderExecutionTarget target,
            String resolvedPhysicalModel,
            AiTokenUsage usage
    );
}
