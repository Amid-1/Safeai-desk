package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateKnowledgeDocumentRequest(
        @NotBlank
        String name,

        @NotNull
        Boolean enabled,

        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
}
