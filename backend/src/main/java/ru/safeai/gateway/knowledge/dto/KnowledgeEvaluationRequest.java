package ru.safeai.gateway.knowledge.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record KnowledgeEvaluationRequest(
        @NotBlank
        String datasetName,

        @Min(1)
        @Max(20)
        Integer topK,

        @NotEmpty
        @Size(max = 100)
        List<@Valid KnowledgeEvaluationCaseRequest> cases
) {
    public KnowledgeEvaluationRequest {
        topK =
                topK == null
                        ? 10
                        : topK;

        cases =
                cases == null
                        ? List.of()
                        : List.copyOf(
                        cases
                );
    }
}
