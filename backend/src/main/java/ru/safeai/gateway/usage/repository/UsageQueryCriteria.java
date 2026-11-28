package ru.safeai.gateway.usage.repository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ru.safeai.gateway.common.budget.PlatformBudgetClassification.Status;

public record UsageQueryCriteria(
        Instant dateFrom,
        Instant dateTo,
        UUID organizationId,
        UUID userId,
        String model,
        String organizationQuery,
        boolean usedOnly,
        BudgetFilter budgetFilter
) {
    public record BudgetFilter(Status status, Instant start, Instant end) {
        public BudgetFilter {
            Objects.requireNonNull(status);
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
            if (!start.isBefore(end)) throw new IllegalArgumentException("Invalid budget period");
        }
    }

    public UsageQueryCriteria(Instant dateFrom, Instant dateTo, UUID organizationId, UUID userId,
                              String model, String organizationQuery, boolean usedOnly) {
        this(dateFrom, dateTo, organizationId, userId, model, organizationQuery, usedOnly, null);
    }
    public UsageQueryCriteria(Instant dateFrom, Instant dateTo, UUID organizationId, UUID userId, String model) {
        this(dateFrom, dateTo, organizationId, userId, model, null, false);
    }

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
