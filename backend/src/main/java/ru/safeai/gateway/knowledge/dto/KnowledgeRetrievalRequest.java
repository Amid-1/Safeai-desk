package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record KnowledgeRetrievalRequest(
        @NotBlank
        String query,

        @Min(1)
        @Max(100)
        Integer topK
) {
}
