package ru.safeai.gateway.ai.pricing;

import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import java.util.UUID;

/** Provider costs must resolve against the precommitted operation/route snapshot. */
public interface PricingResolver {
    PricingResult resolve(
            UUID providerOperationId,
            ProviderExecutionTarget target,
            String resolvedPhysicalModel,
            AiTokenUsage usage);
}
