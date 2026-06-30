package ru.safeai.gateway.ai.provider.mock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.provider.AiProvider;

import java.math.BigDecimal;
import java.util.Objects;

@Slf4j
@Service
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockAiProvider implements AiProvider {

    private static final String MODEL = "mock-safeai";

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        String content = "Mock AI provider response: " + request.userMessage();

        int inputTokens = calculateMockInputTokens(request);
        int outputTokens = estimateTokens(content);

        log.info(
                "Mock AI response generated: userId={}, organizationId={}, chatId={}, model={}, inputTokens={}, outputTokens={}",
                request.userId(),
                request.organizationId(),
                request.chatId(),
                MODEL,
                inputTokens,
                outputTokens
        );

        return new AiChatResponse(
                content,
                MODEL,
                inputTokens,
                outputTokens,
                BigDecimal.ZERO
        );
    }

    private int calculateMockInputTokens(AiChatRequest request) {
        int historyTokens = request.history()
                .stream()
                .filter(Objects::nonNull)
                .map(AiMessage::content)
                .mapToInt(this::estimateTokens)
                .sum();

        return historyTokens + estimateTokens(request.userMessage());
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        return Math.max(1, text.length() / 4);
    }
}