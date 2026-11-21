package ru.safeai.gateway.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Explicit, bounded user confirmation; the server rechecks the stored title. */
public record ChatErasureRequest(
        @NotBlank @Size(max = 255) String confirmationTitle,
        @NotBlank @Size(max = 32) String confirmationPhrase
) { }
