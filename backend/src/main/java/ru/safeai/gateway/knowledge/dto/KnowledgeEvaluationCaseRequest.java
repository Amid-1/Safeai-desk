package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record KnowledgeEvaluationCaseRequest(
        @NotBlank @Size(max = 4000) String query,
        @NotEmpty @Size(max = 100) Set<UUID> expectedDocumentVersionIds
) {
    public KnowledgeEvaluationCaseRequest {
        expectedDocumentVersionIds = expectedDocumentVersionIds == null
                ? Set.of()
                : Set.copyOf(expectedDocumentVersionIds);
    }
}
