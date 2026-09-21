package ru.safeai.gateway.model.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** PostgreSQL timestamptz storage precision, applied before object sealing. */
public final class DatabaseTimestampNormalizer {
    private DatabaseTimestampNormalizer() { }

    public static Instant normalize(Instant value) {
        return Objects.requireNonNull(value, "timestamp не должен быть null")
                .truncatedTo(ChronoUnit.MICROS);
    }
}
