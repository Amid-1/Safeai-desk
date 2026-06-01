package ru.safeai.gateway.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ChatDetailsResponse(
        UUID id,
        String title,
        LocalDateTime createdAt,
        List<MessageResponse> messages
) {
}