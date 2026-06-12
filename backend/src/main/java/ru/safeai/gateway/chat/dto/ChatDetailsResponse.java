package ru.safeai.gateway.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatDetailsResponse(
        UUID id,
        String title,
        Instant createdAt,
        List<MessageResponse> messages
) {
}