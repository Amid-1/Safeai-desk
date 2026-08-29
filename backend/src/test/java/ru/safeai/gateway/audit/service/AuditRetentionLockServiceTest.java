package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditRetentionLockServiceTest {

    private static final long RETENTION_LOCK_KEY =
            6_302_026_071_901L;

    private static final String TRY_LOCK_SQL =
            "select pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL =
            "select pg_advisory_unlock(?)";

    private static final String ACQUIRE_FAILURE_MESSAGE =
            "Не удалось захватить PostgreSQL advisory lock: audit retention";

    private static final String UNLOCK_FAILURE_MESSAGE =
            "Не удалось освободить PostgreSQL advisory lock: audit retention";

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement tryLockStatement;

    @Mock
    private PreparedStatement unlockStatement;

    @Mock
    private ResultSet tryLockResultSet;

    @Mock
    private ResultSet unlockResultSet;

    private AuditRetentionLockService service;

    @BeforeEach
    void setUp() throws SQLException {
        when(dataSource.getConnection())
                .thenReturn(connection);

        service = new AuditRetentionLockService(
                new JdbcTemplate(dataSource)
        );
    }

    @Test
    void acquiredLockExecutesActionAndReleasesLock()
            throws SQLException {
        stubLockAcquired();
        stubUnlockConfirmed();

        AuditRetentionLockService.LockExecution<String> execution =
                service.tryExecute(
                        () -> "result"
                );

        assertThat(execution.acquired())
                .isTrue();
        assertThat(execution.result())
                .isEqualTo("result");

        verify(tryLockStatement).setLong(
                1,
                RETENTION_LOCK_KEY
        );
        verify(unlockStatement).setLong(
                1,
                RETENTION_LOCK_KEY
        );
        verify(unlockStatement).executeQuery();
        verify(connection, never()).abort(
                any(Executor.class)
        );
    }

    @Test
    void unavailableLockSkipsActionAndDoesNotAttemptUnlock()
            throws SQLException {
        stubLockNotAcquired();

        AtomicBoolean actionExecuted =
                new AtomicBoolean(false);

        AuditRetentionLockService.LockExecution<String> execution =
                service.tryExecute(() -> {
                    actionExecuted.set(true);
                    return "must-not-run";
                });

        assertThat(execution.acquired())
                .isFalse();
        assertThat(execution.result())
                .isNull();
        assertThat(actionExecuted.get())
                .isFalse();

        verify(tryLockStatement).setLong(
                1,
                RETENTION_LOCK_KEY
        );
        verify(connection, never()).prepareStatement(
                UNLOCK_SQL
        );
        verify(connection, never()).abort(
                any(Executor.class)
        );
    }

    @Test
    void lockAcquisitionFailureDoesNotRunActionOrAbortConnection()
            throws SQLException {
        when(connection.prepareStatement(
                TRY_LOCK_SQL
        )).thenReturn(
                tryLockStatement
        );

        when(tryLockStatement.executeQuery())
                .thenThrow(
                        new SQLException(
                                "acquire failed"
                        )
                );

        AtomicBoolean actionExecuted =
                new AtomicBoolean(false);

        assertThatThrownBy(() ->
                service.tryExecute(() -> {
                    actionExecuted.set(true);
                    return "must-not-run";
                })
        )
                .isInstanceOf(
                        DataAccessResourceFailureException.class
                )
                .hasMessage(
                        ACQUIRE_FAILURE_MESSAGE
                )
                .hasCauseInstanceOf(
                        SQLException.class
                )
                .satisfies(throwable ->
                        assertThat(throwable.getCause())
                                .hasMessage(
                                        "acquire failed"
                                )
                );

        assertThat(actionExecuted.get())
                .isFalse();

        verify(connection, never()).prepareStatement(
                UNLOCK_SQL
        );
        verify(connection, never()).abort(
                any(Executor.class)
        );
    }

    @Test
    void actionFailureRemainsPrimaryWhenUnlockSucceeds()
            throws SQLException {
        stubLockAcquired();
        stubUnlockConfirmed();

        RuntimeException actionFailure =
                new RuntimeException(
                        "action failed"
                );

        Throwable thrown = catchThrowable(() ->
                service.tryExecute(() -> {
                    throw actionFailure;
                })
        );

        assertThat(thrown)
                .isSameAs(actionFailure);
        assertThat(thrown.getSuppressed())
                .isEmpty();

        verify(unlockStatement).executeQuery();
        verify(connection, never()).abort(
                any(Executor.class)
        );
    }

    @Test
    void unlockFailureAbortsConnectionBeforeItReturnsToPool()
            throws SQLException {
        stubLockAcquired();

        when(connection.prepareStatement(
                UNLOCK_SQL
        )).thenReturn(
                unlockStatement
        );

        when(unlockStatement.executeQuery())
                .thenReturn(
                        unlockResultSet
                );

        when(unlockResultSet.next())
                .thenReturn(true);

        when(unlockResultSet.getBoolean(1))
                .thenReturn(false);

        AtomicBoolean actionExecuted =
                new AtomicBoolean(false);

        assertThatThrownBy(() ->
                service.tryExecute(() -> {
                    actionExecuted.set(true);
                    return "result";
                })
        )
                .isInstanceOf(
                        DataAccessResourceFailureException.class
                )
                .hasMessage(
                        UNLOCK_FAILURE_MESSAGE
                )
                .hasCauseInstanceOf(
                        SQLException.class
                )
                .satisfies(throwable ->
                        assertThat(throwable.getCause())
                                .hasMessage(
                                        "PostgreSQL не подтвердил advisory unlock"
                                )
                );

        assertThat(actionExecuted.get())
                .isTrue();

        verify(connection).abort(
                any(Executor.class)
        );
    }

    @Test
    void actionFailureRemainsPrimaryWhenUnlockAlsoFails()
            throws SQLException {
        stubLockAcquired();

        when(connection.prepareStatement(
                UNLOCK_SQL
        )).thenReturn(
                unlockStatement
        );

        when(unlockStatement.executeQuery())
                .thenThrow(
                        new SQLException(
                                "unlock failed"
                        )
                );

        RuntimeException actionFailure =
                new RuntimeException(
                        "action failed"
                );

        Throwable thrown = catchThrowable(() ->
                service.tryExecute(() -> {
                    throw actionFailure;
                })
        );

        assertThat(thrown)
                .isSameAs(actionFailure);

        assertThat(thrown.getSuppressed())
                .singleElement()
                .satisfies(suppressed -> {
                    assertThat(suppressed)
                            .isInstanceOf(
                                    DataAccessResourceFailureException.class
                            )
                            .hasMessage(
                                    UNLOCK_FAILURE_MESSAGE
                            )
                            .hasCauseInstanceOf(
                                    SQLException.class
                            );

                    assertThat(suppressed.getCause())
                            .hasMessage(
                                    "unlock failed"
                            );
                });

        verify(connection).abort(
                any(Executor.class)
        );
    }

    private void stubLockAcquired()
            throws SQLException {
        when(connection.prepareStatement(
                TRY_LOCK_SQL
        )).thenReturn(
                tryLockStatement
        );

        when(tryLockStatement.executeQuery())
                .thenReturn(
                        tryLockResultSet
                );

        when(tryLockResultSet.next())
                .thenReturn(true);

        when(tryLockResultSet.getBoolean(1))
                .thenReturn(true);
    }

    private void stubLockNotAcquired()
            throws SQLException {
        when(connection.prepareStatement(
                TRY_LOCK_SQL
        )).thenReturn(
                tryLockStatement
        );

        when(tryLockStatement.executeQuery())
                .thenReturn(
                        tryLockResultSet
                );

        when(tryLockResultSet.next())
                .thenReturn(true);

        when(tryLockResultSet.getBoolean(1))
                .thenReturn(false);
    }

    private void stubUnlockConfirmed()
            throws SQLException {
        when(connection.prepareStatement(
                UNLOCK_SQL
        )).thenReturn(
                unlockStatement
        );

        when(unlockStatement.executeQuery())
                .thenReturn(
                        unlockResultSet
                );

        when(unlockResultSet.next())
                .thenReturn(true);

        when(unlockResultSet.getBoolean(1))
                .thenReturn(true);
    }
}
