
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

    private static final String JOB_NAME = "usage-daily-rollup";
    private static final String LOCK_DESCRIPTION = "usage rollup";

    private static final String FIND_LAST_COMPLETED_DATE_SQL = """
            SELECT last_completed_date
            FROM usage_rollup_state
            WHERE job_name = ?
            """;

    private static final String FIND_EARLIEST_USAGE_DATE_SQL = """
            SELECT MIN(usage_date)
            FROM (
                SELECT MIN((created_at AT TIME ZONE 'UTC')::date) AS usage_date
                FROM chat_messages
                WHERE role = 'ASSISTANT'

                UNION ALL

                SELECT MIN((completed_at AT TIME ZONE 'UTC')::date) AS usage_date
                FROM chat_turns
                WHERE state = 'AMBIGUOUS'
                  AND completed_at IS NOT NULL
            ) sources
            """;

    private static final String MARK_COMPLETED_SQL = """
            INSERT INTO usage_rollup_state (
                job_name,
                last_completed_date,
                updated_at
            )
            VALUES (?, ?, CURRENT_TIMESTAMP)

            ON CONFLICT (job_name) DO UPDATE
            SET
                last_completed_date = CASE
                    WHEN usage_rollup_state.last_completed_date IS NULL
                        THEN excluded.last_completed_date

                    WHEN excluded.last_completed_date
                            <= usage_rollup_state.last_completed_date
                        THEN usage_rollup_state.last_completed_date

                    ELSE excluded.last_completed_date
                END,
                updated_at = CURRENT_TIMESTAMP

            WHERE usage_rollup_state.last_completed_date IS NULL
               OR excluded.last_completed_date
                    <= usage_rollup_state.last_completed_date
               OR excluded.last_completed_date
                    = usage_rollup_state.last_completed_date + 1
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public UsageRollupStateRepository(
            UsageJdbcClients jdbcClients,
            DataSource dataSource
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcClients,
                "jdbcClients"
        ).rollup();

        this.dataSource = Objects.requireNonNull(
                dataSource,
                "dataSource"
        );
    }

    /**
     * Возвращает последнюю полностью обработанную UTC-дату.
     *
     * @return последняя обработанная дата или null,
     *         если watermark ещё не инициализирован
     */
    public LocalDate findLastCompletedDate() {
        List<LocalDate> dates = jdbcTemplate.query(
                FIND_LAST_COMPLETED_DATE_SQL,
                (rs, rowNum) -> rs.getObject(
                        "last_completed_date",
                        LocalDate.class
                ),
                JOB_NAME
        );

        return dates.isEmpty() ? null : dates.getFirst();
    }

    /**
     * Определяет самую раннюю UTC-дату,
     * которая может участвовать в Usage Rollup.
     * <p>
     * Учитывает:
     * - сообщения ASSISTANT;
     * - завершённые AMBIGUOUS chat turns.
     * <p>
     * Это позволяет не пропустить usage-события,
     * для которых не было создано полноценное
     * сообщение ASSISTANT.
     *
     * @return самая ранняя дата или null,
     *         если подходящие события отсутствуют
     */
    public LocalDate findEarliestUsageDate() {
        return jdbcTemplate.queryForObject(
                FIND_EARLIEST_USAGE_DATE_SQL,
                LocalDate.class
        );
    }

    /**
     * Продвигает watermark после успешного
     * пересчёта UTC-дня.
     * <p>
     * Инварианты:
     * - watermark не перемещается назад;
     * - повторная запись завершённой даты допустима;
     * - переход вперёд разрешён только на один день;
     * - пропуск необработанных дней запрещён.
     * <p>
     * Проверка выполняется непосредственно в PostgreSQL
     * через условный UPSERT, что исключает разрыв
     * между чтением и обновлением watermark.
     *
     * @param usageDate успешно обработанная UTC-дата
     * @throws IllegalStateException если обнаружен
     *                               пропуск UTC-дня
     */
    public void markCompleted(LocalDate usageDate) {
        Objects.requireNonNull(usageDate, "usageDate");

        int affectedRows = jdbcTemplate.update(
                MARK_COMPLETED_SQL,
                JOB_NAME,
                usageDate
        );

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Usage rollup watermark cannot skip "
                            + "an unprocessed day: "
                            + usageDate
            );
        }
    }

    /**
     * Выполняет задачу пересчёта под PostgreSQL
     * advisory lock.
     * <p>
     * Если блокировка уже занята другим экземпляром
     * приложения, действие не выполняется.
     *
     * @param lockKey ключ advisory lock
     * @param action действие под блокировкой
     * @return true, если блокировка получена;
     *         false, если она занята
     */
    public boolean executeWithAdvisoryLock(
            long lockKey,
            Runnable action
    ) {
        Objects.requireNonNull(action, "action");

        try (Connection connection = dataSource.getConnection()) {
            PostgresAdvisoryLockExecutor.LockExecution<Void> execution =
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
                    "Unable to acquire PostgreSQL "
                            + "advisory lock for usage rollup",
                    exception
            );
        }
    }
}
