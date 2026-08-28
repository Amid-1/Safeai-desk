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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditRetentionLockServiceTest {

    private static final String TRY_LOCK_SQL =
            "select pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL =
            "select pg_advisory_unlock(?)";

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

        when(connection.prepareStatement(
                TRY_LOCK_SQL
        )).thenReturn(
                tryLockStatement
        );

        when(connection.prepareStatement(
                UNLOCK_SQL
        )).thenReturn(
                unlockStatement
        );

        when(tryLockStatement.executeQuery())
                .thenReturn(tryLockResultSet);

        when(tryLockResultSet.next())
                .thenReturn(true);

        when(tryLockResultSet.getBoolean(1))
                .thenReturn(true);

        service = new AuditRetentionLockService(
                new JdbcTemplate(dataSource)
        );
    }

    @Test
    void unlockFailureAbortsConnectionBeforeItReturnsToPool()
            throws SQLException {
        when(unlockStatement.executeQuery())
                .thenReturn(unlockResultSet);

        when(unlockResultSet.next())
                .thenReturn(true);

        when(unlockResultSet.getBoolean(1))
                .thenReturn(false);

        assertThatThrownBy(() ->
                service.tryExecute(
                        () -> "result"
                )
        )
                .isInstanceOf(
                        DataAccessResourceFailureException.class
                )
                .hasMessageContaining(
                        "advisory lock audit retention"
                )
                .hasCauseInstanceOf(
                        SQLException.class
                );

        verify(connection).abort(
                any(Executor.class)
        );
    }

    @Test
    void actionFailureRemainsPrimaryWhenUnlockAlsoFails()
            throws SQLException {
        RuntimeException actionFailure =
                new RuntimeException(
                        "action failed"
                );

        when(unlockStatement.executeQuery())
                .thenThrow(
                        new SQLException(
                                "unlock failed"
                        )
                );

        Throwable thrown = catchThrowable(() ->
                service.tryExecute(() -> {
                    throw actionFailure;
                })
        );

        assertThat(thrown)
                .isSameAs(actionFailure);

        assertThat(
                thrown.getSuppressed()
        )
                .hasSize(1)
                .allSatisfy(suppressed ->
                        assertThat(suppressed)
                                .isInstanceOf(
                                        DataAccessResourceFailureException.class
                                )
                                .hasMessageContaining(
                                        "advisory lock audit retention"
                                )
                );

        verify(connection).abort(
                any(Executor.class)
        );
    }
}
