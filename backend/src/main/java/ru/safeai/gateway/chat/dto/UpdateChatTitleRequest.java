package ru.safeai.gateway.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Maximum title length is enforced by ChatTitlePolicy in Unicode code points.
 */
public record UpdateChatTitleRequest(
        @NotBlank
        String title
) {
}
