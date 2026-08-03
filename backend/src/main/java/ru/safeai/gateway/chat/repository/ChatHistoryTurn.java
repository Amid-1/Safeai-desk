package ru.safeai.gateway.chat.repository;

import java.util.UUID;

public record ChatHistoryTurn(
        UUID turnId,
        String userContent,
        String assistantContent
) {
}
