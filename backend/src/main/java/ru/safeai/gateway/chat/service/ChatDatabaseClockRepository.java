package ru.safeai.gateway.chat.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * Shared PostgreSQL wall clock for distributed Chat lease/fencing decisions.
 *
 * <p>Uses PostgreSQL {@code clock_timestamp()} directly. This keeps the
 * distributed-time invariant while avoiding an unnecessary runtime dependency
 * on a migration-version-named wrapper function.</p>
 */
@Repository
public class ChatDatabaseClockRepository {

    private static final String CURRENT_DATABASE_TIME_SQL =
            "select clock_timestamp()";

    private final JdbcTemplate jdbcTemplate;

    public ChatDatabaseClockRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate не должен быть null"
                );
    }

    public Instant now() {
        Timestamp value =
                jdbcTemplate.queryForObject(
                        CURRENT_DATABASE_TIME_SQL,
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
