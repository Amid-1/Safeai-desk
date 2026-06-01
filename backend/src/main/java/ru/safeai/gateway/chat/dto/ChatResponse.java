package ru.safeai.gateway.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ChatResponse(
        UUID id,
        String title,
        LocalDateTime createdAt
) {
}