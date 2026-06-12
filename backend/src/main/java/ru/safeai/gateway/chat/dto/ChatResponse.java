package ru.safeai.gateway.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatResponse(
        UUID id,
        String title,
        Instant createdAt
) {
}