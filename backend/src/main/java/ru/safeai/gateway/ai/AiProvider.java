package ru.safeai.gateway.ai;

public interface AiProvider {

    AiChatResponse sendMessage(AiChatRequest request);
}