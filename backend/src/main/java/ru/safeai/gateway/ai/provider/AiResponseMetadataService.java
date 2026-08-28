package ru.safeai.gateway.ai.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.pricing.PricingResult;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class AiResponseMetadataService {

    private final ModelPricingService pricingService;

    public AiResponseMetadata extract(
            JsonNode response,
            String resolvedModel
    ) {
        AiTokenUsage tokenUsage =
                AiProviderSupport.extractTokenUsage(
                        response
                );

        UsageStatus usageStatus =
                tokenUsage.usageStatus();

        PricingResult pricing =
                pricingService.calculate(
                        resolvedModel,
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
    }
}
