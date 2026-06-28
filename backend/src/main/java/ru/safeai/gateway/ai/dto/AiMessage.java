package ru.safeai.gateway.ai.dto;

public record AiMessage(
        String role,
        String content
) {

    public AiMessage {
        role = role == null ? "" : role.trim().toUpperCase();
        content = content == null ? "" : content;
    }
}