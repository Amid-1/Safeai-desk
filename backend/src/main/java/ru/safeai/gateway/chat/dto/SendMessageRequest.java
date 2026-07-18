package ru.safeai.gateway.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendMessageRequest(
        @NotBlank
        @Size(max = 10000)
        String content,

        UUID clientRequestId
) {
}
