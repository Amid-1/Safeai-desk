package ru.safeai.gateway.ai.testsupport;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.math.BigDecimal;

/** Additive fixtures: do not change existing AiTestFixtures.freeResponse(),
 * whose deliberately differing requested/resolved models are useful in other tests. */
public final class GovernedAiTestFixtures {
    private GovernedAiTestFixtures() { }

    public static AiChatRequest requestWithCaps(long reservedInputTokens, int maxOutputTokens) {
        AiChatRequest base = AiTestFixtures.request();
        return new AiChatRequest(base.userId(), base.organizationId(), base.chatId(),
                base.providerOperationId(), base.systemInstructions(),
                base.developerInstructions(), base.userMessage(), base.history(),
                reservedInputTokens, maxOutputTokens);
    }

    public static AiChatResponse exactFreeResponse(String physicalModel) {
        return AiChatResponse.fromProvider("Ответ", physicalModel, physicalModel,
                "physical-message-id", "physical-request-id", AiResponseStatus.COMPLETED,
                "completed", AiTokenUsage.basic(1, 1),
                new PricingResult(PricingStatus.FREE,
                        new BigDecimal("0.000000000000"), "USD", "test-free-v1",
                        AiTestFixtures.NOW));
    }

    public static AiChatResponse specializedUnpricedResponse(String physicalModel) {
        return AiChatResponse.fromProvider("Ответ", physicalModel, physicalModel,
                "physical-message-id", "physical-request-id", AiResponseStatus.COMPLETED,
                "completed", new AiTokenUsage(1_000, 800, 200, 100, true, true),
                PricingResult.unpriced(AiTestFixtures.NOW));
    }
}
