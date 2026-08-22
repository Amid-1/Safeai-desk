package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;

public record UpdateKnowledgeBaseRequest(
        @NotBlank
        String name,

        String description,

        @NotNull
        KnowledgeBaseVisibility visibility,

        @NotNull
        Boolean enabled,

        @NotNull
        @PositiveOrZero
        Long expectedVersion
) {
}
