package ru.safeai.gateway.usage.dto;

import java.util.Objects;

public record UsageModelSummaryResponse(
        String model,
        UsageResponseSummary responses,
        UsageTokenSummary usage,
        UsageCostSummary cost
) {

    public UsageModelSummaryResponse {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "model не должен быть пустым"
            );
        }

        model = model.trim();

        Objects.requireNonNull(
                responses,
                "responses не должен быть null"
        );

        Objects.requireNonNull(
                usage,
                "usage не должен быть null"
        );

        Objects.requireNonNull(
                cost,
                "cost не должен быть null"
        );

        UsageSummaryInvariants.validate(
                responses,
                usage,
                cost
        );
    }
}