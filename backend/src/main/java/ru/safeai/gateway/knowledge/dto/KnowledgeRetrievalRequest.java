package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeRetrievalRequest(
        @NotBlank
        @Size(max = 4_000)
        String query,

        @Min(1)
        @Max(20)
        Integer topK
) {
}
