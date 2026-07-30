package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditRetentionLockService {

    private static final long RETENTION_LOCK_KEY =
            6_302_026_071_901L;

    private final JdbcTemplate jdbcTemplate;

    public <T> LockExecution<T> tryExecute(
            Supplier<T> action
    ) {
        Objects.requireNonNull(
                action,
                "action не должен быть null"
        );

        ConnectionCallback<LockExecution<T>> callback =
                connection -> {
                    if (!tryLock(connection)) {
                        return LockExecution.notAcquired();
                    }

                    try {
                        return LockExecution.acquired(
                                action.get()
                        );
                    } finally {
                        unlock(connection);
                    }
                };

        LockExecution<T> result =
                jdbcTemplate.execute(callback);

        return Objects.requireNonNull(
                result,
                "JdbcTemplate returned null lock result"
        );
    }

    private boolean tryLock(Connection connection)
            throws SQLException {

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "select pg_try_advisory_lock(?)"
                     )) {
            statement.setLong(
                    1,
                    RETENTION_LOCK_KEY
            );

            try (ResultSet resultSet =
                         statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "PostgreSQL advisory lock "
                                    + "did not return a row"
                    );
                }

                return resultSet.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) {
        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "select pg_advisory_unlock(?)"
                     )) {
            statement.setLong(
                    1,
                    RETENTION_LOCK_KEY
            );

            try (ResultSet resultSet =
                         statement.executeQuery()) {
                if (!resultSet.next()
                        || !resultSet.getBoolean(1)) {
                    log.error(
                            "Audit retention advisory lock "
                                    + "was not released"
                    );
                }
            }
        } catch (SQLException exception) {
            /*
             * A session-level advisory lock is also released when the
             * connection closes. Logging remains mandatory because a pooled
             * connection may otherwise retain the lock.
             */
            log.error(
                    "Unable to release audit retention "
                            + "advisory lock",
                    exception
            );
        }
    }

    public record LockExecution<T>(
            boolean acquired,
            T result
    ) {
        public static <T> LockExecution<T>
        notAcquired() {
            return new LockExecution<>(
                    false,
                    null
            );
        }

        public static <T> LockExecution<T>
        acquired(T result) {
            return new LockExecution<>(
                    true,
                    result
            );
        }
    }
}
