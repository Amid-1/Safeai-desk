package ru.safeai.gateway.usage.dto;

import java.time.LocalDate;
import java.util.Objects;

public record UsageDailySummaryResponse(
        LocalDate usageDate,
        String aggregationZone,
        UsageResponseSummary responses,
        UsageTokenSummary usage,
        UsageCostSummary cost
) {

    private static final String SUPPORTED_AGGREGATION_ZONE = "UTC";

    public UsageDailySummaryResponse {
        Objects.requireNonNull(
                usageDate,
                "usageDate не должен быть null"
        );

        if (!SUPPORTED_AGGREGATION_ZONE.equals(aggregationZone)) {
            throw new IllegalArgumentException(
                    "Daily usage в текущем API агрегируется только по UTC"
            );
        }

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