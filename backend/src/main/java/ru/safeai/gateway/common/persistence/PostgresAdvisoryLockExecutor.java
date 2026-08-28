package ru.safeai.gateway.common.persistence;

import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Низкоуровневый executor для session-level PostgreSQL advisory locks.
 *
 * <p>Получение lock, выполнение action и освобождение lock обязательно
 * выполняются на одном JDBC connection, то есть в рамках одной PostgreSQL
 * session.</p>
 *
 * <p>Если {@code pg_advisory_unlock()} завершается ошибкой либо сообщает,
 * что lock не был освобождён, connection аварийно закрывается через
 * {@link Connection#abort(java.util.concurrent.Executor)} best-effort.
 * Это предотвращает возврат в pool PostgreSQL session, которая потенциально
 * продолжает владеть session-level advisory lock.</p>
 *
 * <p>Если одновременно завершились ошибкой action и unlock, исходная
 * ошибка action остаётся основной, а ошибка unlock добавляется к ней
 * как suppressed.</p>
 */
public final class PostgresAdvisoryLockExecutor {

    private static final String TRY_LOCK_SQL =
            "select pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL =
            "select pg_advisory_unlock(?)";

    private PostgresAdvisoryLockExecutor() {
    }

    public static <T> LockExecution<T> tryExecute(
            Connection connection,
            long lockKey,
            String lockDescription,
            Supplier<T> action
    ) throws SQLException {
        Objects.requireNonNull(
                connection,
                "connection не должен быть null"
        );

        Objects.requireNonNull(
                lockDescription,
                "lockDescription не должен быть null"
        );

        Objects.requireNonNull(
                action,
                "action не должен быть null"
        );

        if (lockDescription.isBlank()) {
            throw new IllegalArgumentException(
                    "lockDescription не должен быть пустым"
            );
        }

        if (!tryLock(
                connection,
                lockKey
        )) {
            return LockExecution.notAcquired();
        }

        Throwable actionFailure = null;

        try {
            return LockExecution.acquired(
                    action.get()
            );
        } catch (RuntimeException | Error exception) {
            actionFailure = exception;
            throw exception;
        } finally {
            try {
                unlock(
                        connection,
                        lockKey,
                        lockDescription
                );
            } catch (SQLException exception) {
                DataAccessResourceFailureException unlockFailure =
                        new DataAccessResourceFailureException(
                                "Не удалось освободить PostgreSQL "
                                        + "advisory lock "
                                        + lockDescription,
                                exception
                        );

                abortConnectionAfterUnlockFailure(
                        connection,
                        unlockFailure
                );

                if (actionFailure != null) {
                    actionFailure.addSuppressed(
                            unlockFailure
                    );
                } else {
                    throw unlockFailure;
                }
            }
        }
    }

    private static boolean tryLock(
            Connection connection,
            long lockKey
    ) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             TRY_LOCK_SQL
                     )) {

            statement.setLong(
                    1,
                    lockKey
            );

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                if (!resultSet.next()) {
                    throw new SQLException(
                            "pg_try_advisory_lock не вернул строку"
                    );
                }

                return resultSet.getBoolean(
                        1
                );
            }
        }
    }

    private static void unlock(
            Connection connection,
            long lockKey,
            String lockDescription
    ) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             UNLOCK_SQL
                     )) {

            statement.setLong(
                    1,
                    lockKey
            );

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                if (!resultSet.next()
                        || !resultSet.getBoolean(1)) {

                    throw new SQLException(
                            "PostgreSQL advisory lock "
                                    + lockDescription
                                    + " не был освобождён"
                    );
                }
            }
        }
    }

    private static void abortConnectionAfterUnlockFailure(
            Connection connection,
            DataAccessResourceFailureException unlockFailure
    ) {
        try {
            connection.abort(
                    Runnable::run
            );
        } catch (SQLException | RuntimeException abortException) {
            unlockFailure.addSuppressed(
                    abortException
            );
        }
    }

    public record LockExecution<T>(
            boolean acquired,
            T result
    ) {

        public static <T> LockExecution<T> notAcquired() {
            return new LockExecution<>(
                    false,
                    null
            );
        }

        public static <T> LockExecution<T> acquired(
                T result
        ) {
            return new LockExecution<>(
                    true,
                    result
            );
        }
    }
}