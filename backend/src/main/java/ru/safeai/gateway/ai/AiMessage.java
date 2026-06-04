package ru.safeai.gateway.ai;

public record AiMessage(
        String role,
        String content
) {
}
