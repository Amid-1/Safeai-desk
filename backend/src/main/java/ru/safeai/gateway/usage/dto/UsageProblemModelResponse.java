package ru.safeai.gateway.usage.dto;

public record UsageProblemModelResponse(
        String model,
        long usageProblems,
        long pricingProblems
) {
    public UsageProblemModelResponse {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "model не должен быть пустым"
            );
        }

        model = model.trim();

        if (usageProblems < 0 || pricingProblems < 0) {
            throw new IllegalArgumentException(
                    "Счётчики проблем не могут быть отрицательными"
            );
        }
    }
}
