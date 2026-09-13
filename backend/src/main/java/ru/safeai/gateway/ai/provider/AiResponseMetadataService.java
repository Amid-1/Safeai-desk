package ru.safeai.gateway.ai.provider;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.execution.ProviderExecutionTarget;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.PricingResolver;
import ru.safeai.gateway.ai.pricing.PricingResult;
import tools.jackson.databind.JsonNode;

@Service
public class AiResponseMetadataService {

    private final PricingResolver pricingResolver;

    public AiResponseMetadataService(PricingResolver pricingResolver) {
        this.pricingResolver = pricingResolver;
    }

    public AiResponseMetadata extract(
            JsonNode response,
            ProviderExecutionTarget target,
            String resolvedPhysicalModel
    ) {
        AiTokenUsage tokenUsage =
                AiProviderSupport.extractTokenUsage(
                        response
                );

        UsageStatus usageStatus =
                tokenUsage.usageStatus();

        PricingResult pricing =
                pricingResolver.resolve(
                        target,
                        resolvedPhysicalModel,
                        tokenUsage
                );

        return new AiResponseMetadata(
                tokenUsage.inputTokens(),
                tokenUsage.cachedInputTokens(),
                tokenUsage.cacheWriteInputTokens(),
                tokenUsage.outputTokens(),
                usageStatus,
                tokenUsage.specializedBillingDimensionsPresent(),
                tokenUsage.specializedBillingDimensionsValid(),
                pricing
        );
    }

    public record AiResponseMetadata(
            Integer inputTokens,
            Integer cachedInputTokens,
            Integer cacheWriteInputTokens,
            Integer outputTokens,
            UsageStatus usageStatus,
            boolean specializedBillingDimensionsPresent,
            boolean specializedBillingDimensionsValid,
            PricingResult pricing
    ) {
        public AiTokenUsage usageEvidence() {
            return new AiTokenUsage(
                    inputTokens,
                    cachedInputTokens,
                    cacheWriteInputTokens,
                    outputTokens,
                    specializedBillingDimensionsPresent,
                    specializedBillingDimensionsValid
            );
        }
    }
}
