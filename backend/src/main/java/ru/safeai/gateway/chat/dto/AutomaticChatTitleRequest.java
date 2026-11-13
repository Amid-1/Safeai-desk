package ru.safeai.gateway.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** First submitted user text; the title is calculated only by the server. */
public record AutomaticChatTitleRequest(
        @NotBlank @Size(max = 16000) String content
) { }
