package ru.safeai.gateway.ai;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
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
        if (request.userMessage() == null || request.userMessage().isBlank()) {
            return 0;
        }

        return Math.max(1, request.userMessage().length() / 4);
    }

    private Integer calculateMockOutputTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        return Math.max(1, content.length() / 4);
    }
}