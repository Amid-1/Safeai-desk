package ru.safeai.gateway.usage.repository;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class UsageRollupStateRepository {

    private static final String JOB_NAME =
            "usage-daily-rollup";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public UsageRollupStateRepository(
            UsageJdbcClients jdbcClients,
            DataSource dataSource
    ) {
        this.jdbcTemplate = jdbcClients.rollup();
        this.dataSource = dataSource;
    }

    public LocalDate findLastCompletedDate() {
        List<LocalDate> values = jdbcTemplate.query(
                """
                select last_completed_date
                from usage_rollup_state
                where job_name = ?
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject(
                                "last_completed_date",
                                LocalDate.class
                        ),
                JOB_NAME
        );

        return values.isEmpty() ? null : values.getFirst();
    }

    public LocalDate findEarliestAssistantDate() {
        return jdbcTemplate.queryForObject(
                """
                select min(
                    date(created_at at time zone 'UTC')
                )
                from chat_messages
                where role = 'ASSISTANT'
                """,
                LocalDate.class
        );
    }

    public void markCompleted(LocalDate usageDate) {
        Objects.requireNonNull(
                usageDate,
                "usageDate не должен быть null"
        );

        int affectedRows = jdbcTemplate.update(
                """
                insert into usage_rollup_state (
                    job_name,
                    last_completed_date,
                    updated_at
                ) values (?, ?, current_timestamp)
                on conflict (job_name) do update
                set last_completed_date = case
                        when usage_rollup_state.last_completed_date is null
                            then excluded.last_completed_date
                        when excluded.last_completed_date
                                <= usage_rollup_state.last_completed_date
                            then usage_rollup_state.last_completed_date
                        else excluded.last_completed_date
                    end,
                    updated_at = current_timestamp
                where usage_rollup_state.last_completed_date is null
                   or excluded.last_completed_date
                        <= usage_rollup_state.last_completed_date
                   or excluded.last_completed_date
                        = usage_rollup_state.last_completed_date + 1
                """,
                JOB_NAME,
                usageDate
        );

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Usage rollup watermark нельзя продвинуть с пропуском "
                            + "UTC-дня: "
                            + usageDate
            );
        }
    }

    /**
     * Session-level advisory locks must be acquired and released on the same
     * physical PostgreSQL connection. JdbcTemplate calls alone cannot provide
     * that guarantee outside a transaction, therefore this method keeps a
     * dedicated pooled connection for the complete rollup run.
     */
    public boolean executeWithAdvisoryLock(
            long lockKey,
            Runnable action
    ) {
        Objects.requireNonNull(action, "action не должен быть null");

        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, lockKey)) {
                return false;
            }

            Throwable actionFailure = null;

            try {
                action.run();
                return true;
            } catch (RuntimeException | Error exception) {
                actionFailure = exception;
                throw exception;
            } finally {
                try {
                    unlock(connection, lockKey);
                } catch (SQLException exception) {
                    DataAccessResourceFailureException unlockFailure =
                            new DataAccessResourceFailureException(
                                    "Не удалось освободить PostgreSQL "
                                            + "advisory lock usage rollup",
                                    exception
                            );

                    if (actionFailure != null) {
                        actionFailure.addSuppressed(unlockFailure);
                    } else {
                        throw unlockFailure;
                    }
                }
            }
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException(
                    "Не удалось выполнить PostgreSQL advisory lock "
                            + "для usage rollup",
                    exception
            );
        }
    }

    private boolean tryLock(
            Connection connection,
            long lockKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_try_advisory_lock(?)"
        )) {
            statement.setLong(1, lockKey);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException(
                            "pg_try_advisory_lock не вернул строку"
                    );
                }

                return resultSet.getBoolean(1);
            }
        }
    }

    private void unlock(
            Connection connection,
            long lockKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_advisory_unlock(?)"
        )) {
            statement.setLong(1, lockKey);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || !resultSet.getBoolean(1)) {
                    throw new SQLException(
                            "PostgreSQL advisory lock usage rollup "
                                    + "не был освобождён"
                    );
                }
            }
        }
    }
}
