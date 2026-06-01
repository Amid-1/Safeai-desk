package ru.safeai.gateway.chat.dto;

import jakarta.validation.constraints.Size;

public record CreateChatRequest(
        @Size(max = 255)
        String title
) {
}