package ru.safeai.gateway.common.persistence;

import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Executes a callback while holding a session-level PostgreSQL advisory lock.
 *
 * <p>The caller owns the connection lifecycle, but this class guarantees that
 * the lock is acquired and released on that very same connection. A connection
 * for which {@code pg_advisory_unlock} cannot be confirmed is aborted before it
 * may return to a pool: otherwise a later request could inherit the lock.</p>
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
    ) {
        Objects.requireNonNull(connection, "connection не должен быть null");
        Objects.requireNonNull(lockDescription, "lockDescription не должен быть null");
        Objects.requireNonNull(action, "action не должен быть null");

        boolean acquired = tryAcquire(
                connection,
                lockKey,
                lockDescription
        );

        if (!acquired) {
            return LockExecution.notAcquired();
        }

        final T result;

        try {
            result = action.get();
        } catch (RuntimeException | Error actionFailure) {
            releaseAfterActionFailure(
                    connection,
                    lockKey,
                    lockDescription,
                    actionFailure
            );

            throw actionFailure;
        }

        releaseOrAbort(
                connection,
                lockKey,
                lockDescription
        );

        return LockExecution.acquired(result);
    }

    private static boolean tryAcquire(
            Connection connection,
            long lockKey,
            String lockDescription
    ) {
        try (PreparedStatement statement =
                     connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setLong(1, lockKey);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw dataAccessFailure(
                            "PostgreSQL не вернул результат при захвате "
                                    + lockDescription,
                            null
                    );
                }

                return resultSet.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw dataAccessFailure(
                    "Не удалось захватить PostgreSQL advisory lock: "
                            + lockDescription,
                    exception
            );
        }
    }

    private static void releaseAfterActionFailure(
            Connection connection,
            long lockKey,
            String lockDescription,
            Throwable actionFailure
    ) {
        try {
            release(connection, lockKey);
        } catch (SQLException unlockFailure) {
            abortConnection(connection, unlockFailure);
            actionFailure.addSuppressed(
                    dataAccessFailure(
                            "Не удалось освободить PostgreSQL advisory lock: "
                                    + lockDescription,
                            unlockFailure
                    )
            );
        }
    }

    private static void releaseOrAbort(
            Connection connection,
            long lockKey,
            String lockDescription
    ) {
        try {
            release(connection, lockKey);
        } catch (SQLException unlockFailure) {
            abortConnection(connection, unlockFailure);

            throw dataAccessFailure(
                    "Не удалось освободить PostgreSQL advisory lock: "
                            + lockDescription,
                    unlockFailure
            );
        }
    }

    private static void release(
            Connection connection,
            long lockKey
    ) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(UNLOCK_SQL)) {
            statement.setLong(1, lockKey);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || !resultSet.getBoolean(1)) {
                    throw new SQLException(
                            "PostgreSQL не подтвердил advisory unlock"
                    );
                }
            }
        }
    }

    private static void abortConnection(
            Connection connection,
            Throwable rootFailure
    ) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException abortFailure) {
            rootFailure.addSuppressed(abortFailure);
        }
    }

    private static DataAccessResourceFailureException dataAccessFailure(
            String message,
            Throwable cause
    ) {
        return cause == null
                ? new DataAccessResourceFailureException(message)
                : new DataAccessResourceFailureException(message, cause);
    }

    public record LockExecution<T>(
            boolean acquired,
            T result
    ) {

        private static <T> LockExecution<T> notAcquired() {
            return new LockExecution<>(false, null);
        }

        private static <T> LockExecution<T> acquired(T result) {
            return new LockExecution<>(true, result);
        }
    }
}
