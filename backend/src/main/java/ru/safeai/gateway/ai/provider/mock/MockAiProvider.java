package ru.safeai.gateway.ai.provider.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.provider.AiProvider;

import java.math.BigDecimal;
import java.util.Objects;

@Service
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockAiProvider implements AiProvider {

    private static final String MOCK_MODEL = "mock-safeai";

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        String content = "Mock AI provider response: " + request.userMessage();

        return new AiChatResponse(
                content,
                MOCK_MODEL,
                calculateMockInputTokens(request),
                calculateMockOutputTokens(content),
                BigDecimal.ZERO
        );
    }

    private Integer calculateMockInputTokens(AiChatRequest request) {
        int historyTokens = request.history()
                .stream()
                .filter(Objects::nonNull)
                .map(AiMessage::content)
                .mapToInt(this::estimateTokens)
                .sum();

        return historyTokens + estimateTokens(request.userMessage());
    }

    private Integer calculateMockOutputTokens(String content) {
        return estimateTokens(content);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        return Math.max(1, text.length() / 4);
    }
}