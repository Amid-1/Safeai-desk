// backend/src/main/java/ru/safeai/gateway/chat/dto/UpdateChatTitleRequest.java
package ru.safeai.gateway.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateChatTitleRequest(
        @NotBlank
        @Size(max = 255)
        String title
) {
}
