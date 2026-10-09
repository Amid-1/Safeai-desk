package ru.safeai.gateway.audit.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * PostgreSQL wall clock used by distributed audit durability operations.
 *
 * <p>Application JVM clocks are unsuitable for retention cutoffs and
 * cross-node retry scheduling because clock skew can otherwise expire evidence
 * early or make retry ordering inconsistent.</p>
 */
@Component
public final class AuditDatabaseClock {

    private final JdbcTemplate jdbcTemplate;

    public AuditDatabaseClock(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate не должен быть null"
        );
    }

    public Instant now() {
        Timestamp value =
                jdbcTemplate.queryForObject(
                        "select clock_timestamp()",
                        Timestamp.class
                );

        if (value == null) {
            throw new IllegalStateException(
                    "PostgreSQL clock_timestamp() вернул null"
            );
        }

        return value.toInstant();
    }
}
