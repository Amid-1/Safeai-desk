package ru.safeai.gateway.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        UUID chatId,
        UUID turnId,
        UUID clientRequestId,
        Long retryAfterSeconds
) {
}
