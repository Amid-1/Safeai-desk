package ru.safeai.gateway.usage.repository;

import java.time.Instant;
import java.util.Objects;

public record UsageInstantRange(
        Instant from,
        Instant to
) {

    public UsageInstantRange {
        Objects.requireNonNull(
                from,
                "from не должен быть null"
        );

        Objects.requireNonNull(
                to,
                "to не должен быть null"
        );

        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "from должен быть раньше to"
            );
        }
    }
}