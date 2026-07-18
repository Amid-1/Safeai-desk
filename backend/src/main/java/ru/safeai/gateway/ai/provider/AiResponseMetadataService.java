package ru.safeai.gateway.ai.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatResponse;
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
            String model
    ) {
        Integer inputTokens =
                AiProviderSupport.extractInputTokens(
                        response
                );

        Integer outputTokens =
                AiProviderSupport.extractOutputTokens(
                        response
                );

        UsageStatus usageStatus =
                AiChatResponse.determineUsageStatus(
                        inputTokens,
                        outputTokens
                );

        PricingResult pricing =
                pricingService.calculate(
                        model,
                        inputTokens,
                        outputTokens,
                        usageStatus
                );

        return new AiResponseMetadata(
                inputTokens,
                outputTokens,
                usageStatus,
                pricing
        );
    }

    public record AiResponseMetadata(
            Integer inputTokens,
            Integer outputTokens,
            UsageStatus usageStatus,
            PricingResult pricing
    ) {
    }
}