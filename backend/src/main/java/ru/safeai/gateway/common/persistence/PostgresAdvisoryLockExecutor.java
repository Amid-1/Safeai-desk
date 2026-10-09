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
 * acquire, action and release use that very same PostgreSQL session.</p>
 *
 * <p>A session for which lock acquisition or unlock cannot be confirmed is
 * treated as unsafe and is aborted before it may return to a pool. This is
 * required because session-level advisory locks survive transaction rollback
 * and live until explicit unlock or PostgreSQL session termination.</p>
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

        return LockExecution.acquired(
                result
        );
    }

    private static boolean tryAcquire(
            Connection connection,
            long lockKey,
            String lockDescription
    ) {
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
                    /*
                     * Query reached PostgreSQL but the expected confirmation
                     * was not observed. For a session-level lock this is an
                     * ambiguous acquire boundary: never return the connection
                     * to a pool.
                     */
                    throw new SQLException(
                            "PostgreSQL не подтвердил результат "
                                    + "pg_try_advisory_lock"
                    );
                }

                return resultSet.getBoolean(
                        1
                );
            }
        } catch (SQLException acquireFailure) {
            /*
             * The server may have acquired the session-level advisory lock
             * before the client observed the failure. Rollback is insufficient:
             * terminate the session best-effort before propagating the error.
             */
            abortConnection(
                    connection,
                    acquireFailure
            );

            throw dataAccessFailure(
                    "Не удалось подтвердить захват PostgreSQL advisory lock: "
                            + lockDescription,
                    acquireFailure
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
            release(
                    connection,
                    lockKey
            );
        } catch (SQLException unlockFailure) {
            abortConnection(
                    connection,
                    unlockFailure
            );

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
            release(
                    connection,
                    lockKey
            );
        } catch (SQLException unlockFailure) {
            abortConnection(
                    connection,
                    unlockFailure
            );

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
                        || !resultSet.getBoolean(
                        1
                )) {
                    throw new SQLException(
                            "PostgreSQL не подтвердил advisory unlock"
                    );
                }
            }
        }
    }

    /**
     * Makes a potentially lock-owning pooled connection unusable best-effort.
     *
     * <p>{@link Connection#abort(java.util.concurrent.Executor)} is preferred
     * because ordinary {@code close()} on a pool proxy may mean "return to
     * pool". If abort itself fails, close is still attempted as a final
     * defensive fallback and all cleanup failures are attached to the original
     * failure.</p>
     */
    private static void abortConnection(
            Connection connection,
            Throwable rootFailure
    ) {
        try {
            connection.abort(
                    Runnable::run
            );
            return;
        } catch (SQLException abortFailure) {
            rootFailure.addSuppressed(
                    abortFailure
            );
        }

        try {
            connection.close();
        } catch (SQLException closeFailure) {
            rootFailure.addSuppressed(
                    closeFailure
            );
        }
    }

    private static DataAccessResourceFailureException dataAccessFailure(
            String message,
            Throwable cause
    ) {
        return cause == null
                ? new DataAccessResourceFailureException(
                message
        )
                : new DataAccessResourceFailureException(
                message,
                cause
        );
    }

    public record LockExecution<T>(
            boolean acquired,
            T result
    ) {

        private static <T> LockExecution<T> notAcquired() {
            return new LockExecution<>(
                    false,
                    null
            );
        }

        private static <T> LockExecution<T> acquired(
                T result
        ) {
            return new LockExecution<>(
                    true,
                    result
            );
        }
    }
}
