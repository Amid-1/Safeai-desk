package ru.safeai.gateway.chat.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        String role,
        String content,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd,
        LocalDateTime createdAt
) {
}