package ru.safeai.gateway.ai.provider;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;

public interface AiProvider {

    AiChatResponse sendMessage(AiChatRequest request);
}