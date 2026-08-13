package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;

public record CreateKnowledgeBaseRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 2_000)
        String description,

        @NotNull
        KnowledgeBaseVisibility visibility
) {
}
