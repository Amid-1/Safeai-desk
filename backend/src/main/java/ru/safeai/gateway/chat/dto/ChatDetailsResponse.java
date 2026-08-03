package ru.safeai.gateway.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ChatDetailsResponse(
        UUID id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<MessageResponse> messages
) {
    public ChatDetailsResponse {
        messages = List.copyOf(
                Objects.requireNonNull(messages, "messages не должен быть null")
        );
    }
}
