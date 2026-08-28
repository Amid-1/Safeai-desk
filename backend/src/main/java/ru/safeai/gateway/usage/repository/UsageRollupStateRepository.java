package ru.safeai.gateway.usage.repository;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.safeai.gateway.common.persistence.PostgresAdvisoryLockExecutor;
import ru.safeai.gateway.usage.config.UsageJdbcClients;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class UsageRollupStateRepository {

    private static final String JOB_NAME =
            "usage-daily-rollup";

    private static final String LOCK_DESCRIPTION =
            "usage rollup";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public UsageRollupStateRepository(
            UsageJdbcClients jdbcClients,
            DataSource dataSource
    ) {
        this.jdbcTemplate =
                jdbcClients.rollup();

        this.dataSource =
                dataSource;
    }

    public LocalDate findLastCompletedDate() {
        List<LocalDate> values =
                jdbcTemplate.query(
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

        return values.isEmpty()
                ? null
                : values.getFirst();
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

    public void markCompleted(
            LocalDate usageDate
    ) {
        Objects.requireNonNull(
                usageDate,
                "usageDate не должен быть null"
        );

        int affectedRows =
                jdbcTemplate.update(
                        """
                        insert into usage_rollup_state (
                            job_name,
                            last_completed_date,
                            updated_at
                        ) values (?, ?, current_timestamp)
                        on conflict (job_name) do update
                        set last_completed_date = case
                                when usage_rollup_state.last_completed_date
                                        is null
                                    then excluded.last_completed_date
                                when excluded.last_completed_date
                                        <= usage_rollup_state
                                        .last_completed_date
                                    then usage_rollup_state
                                        .last_completed_date
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
                    "Usage rollup watermark нельзя продвинуть "
                            + "с пропуском UTC-дня: "
                            + usageDate
            );
        }
    }

    /**
     * Выполняет rollup action только после получения session-level
     * PostgreSQL advisory lock.
     *
     * <p>Для полного run намеренно используется один dedicated pooled
     * connection. Session-level advisory lock должен быть получен и
     * освобождён на одной физической PostgreSQL session.</p>
     *
     * <p>Низкоуровневая lock/unlock/abort semantics централизована в
     * {@link PostgresAdvisoryLockExecutor}. Если explicit unlock не
     * подтверждён, connection аварийно закрывается best-effort до возврата
     * в pool.</p>
     */
    public boolean executeWithAdvisoryLock(
            long lockKey,
            Runnable action
    ) {
        Objects.requireNonNull(
                action,
                "action не должен быть null"
        );

        try (Connection connection =
                     dataSource.getConnection()) {

            PostgresAdvisoryLockExecutor
                    .LockExecution<Void> execution =
                    PostgresAdvisoryLockExecutor.tryExecute(
                            connection,
                            lockKey,
                            LOCK_DESCRIPTION,
                            () -> {
                                action.run();
                                return null;
                            }
                    );

            return execution.acquired();
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException(
                    "Не удалось выполнить PostgreSQL advisory lock "
                            + "для usage rollup",
                    exception
            );
        }
    }
}