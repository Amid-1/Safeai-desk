package ru.safeai.gateway.usage.repository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UsageQueryCriteria(
        Instant dateFrom,
        Instant dateTo,
        UUID organizationId,
        UUID userId,
        String model
) {

    public UsageQueryCriteria {
        Objects.requireNonNull(
                dateFrom,
                "dateFrom не должен быть null"
        );

        Objects.requireNonNull(
                dateTo,
                "dateTo не должен быть null"
        );

        if (!dateFrom.isBefore(dateTo)) {
            throw new IllegalArgumentException(
                    "dateFrom должен быть раньше dateTo"
            );
        }

        model = model == null || model.isBlank()
                ? null
                : model.trim();
    }
}