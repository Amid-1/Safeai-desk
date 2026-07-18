package ru.safeai.gateway.ai.provider.mock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.ModelPricingService;
import ru.safeai.gateway.ai.pricing.PricingResult;
import ru.safeai.gateway.ai.provider.AiProvider;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockAiProvider implements AiProvider {

    private static final String MODEL = "mock-safeai";

    private final ModelPricingService pricingService;

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        String content =
                "Mock AI provider response: "
                        + request.userMessage();

        int inputTokens =
                calculateMockInputTokens(request);
        int outputTokens =
                estimateTokens(content);

        PricingResult pricing =
                pricingService.calculate(
                        MODEL,
                        inputTokens,
                        outputTokens,
                        UsageStatus.AVAILABLE
                );

        log.debug(
                "Mock AI response generated: model={}, inputTokens={}, outputTokens={}, pricingStatus={}",
                MODEL,
                inputTokens,
                outputTokens,
                pricing.status()
        );

        return AiChatResponse.fromProvider(
                content,
                MODEL,
                request.providerOperationId().toString(),
                AiResponseStatus.COMPLETED,
                "mock_completed",
                inputTokens,
                outputTokens,
                pricing
        );
    }

    private int calculateMockInputTokens(
            AiChatRequest request
    ) {
        int historyTokens = request.history()
                .stream()
                .map(AiMessage::content)
                .mapToInt(this::estimateTokens)
                .sum();

        return historyTokens
                + estimateTokens(request.userMessage());
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        return Math.max(1, text.length() / 4);
    }
}
