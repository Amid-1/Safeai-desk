package ru.safeai.gateway.usage.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * currentUserEmail — текущее отображаемое значение пользователя.
 * Историческим идентификатором является userId.
 */
public record UsageSummaryResponse(
        UUID userId,
        String currentUserEmail,
        String model,
        UsageResponseSummary responses,
        UsageTokenSummary usage,
        UsageCostSummary cost
) {

    public UsageSummaryResponse {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        currentUserEmail = requireText(
                currentUserEmail,
                "currentUserEmail"
        );

        model = requireText(
                model,
                "model"
        );

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

    private static String requireText(
            String value,
            String propertyName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    propertyName + " не должен быть пустым"
            );
        }

        return value.trim();
    }
}