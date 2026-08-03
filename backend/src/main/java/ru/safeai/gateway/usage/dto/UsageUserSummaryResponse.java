package ru.safeai.gateway.usage.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * currentUserEmail — текущее отображаемое значение,
 * userId — постоянный идентификатор пользователя.
 */
public record UsageUserSummaryResponse(
        UUID userId,
        String currentUserEmail,
        UsageResponseSummary responses,
        UsageTokenSummary usage,
        UsageCostSummary cost
) {

    public UsageUserSummaryResponse {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        if (currentUserEmail == null
                || currentUserEmail.isBlank()) {
            throw new IllegalArgumentException(
                    "currentUserEmail не должен быть пустым"
            );
        }

        currentUserEmail = currentUserEmail.trim();

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