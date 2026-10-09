package ru.safeai.gateway.common.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresAdvisoryLockExecutorTest {

    @Test
    void ambiguousAcquireFailureAbortsConnection() throws Exception {
        Connection connection =
                mock(
                        Connection.class
                );

        PreparedStatement statement =
                mock(
                        PreparedStatement.class
                );

        when(
                connection.prepareStatement(
                        anyString()
                )
        ).thenReturn(
                statement
        );

        when(
                statement.executeQuery()
        ).thenThrow(
                new SQLException(
                        "network failure after server execution"
                )
        );

        assertThatThrownBy(() ->
                PostgresAdvisoryLockExecutor.tryExecute(
                        connection,
                        42L,
                        "test lock",
                        () -> "never"
                )
        )
                .isInstanceOf(
                        DataAccessResourceFailureException.class
                )
                .hasMessageContaining(
                        "Не удалось подтвердить захват"
                );

        verify(
                connection
        ).abort(
                any(
                        Executor.class
                )
        );
    }

    @Test
    void abortFailureFallsBackToClose() throws Exception {
        Connection connection =
                mock(
                        Connection.class
                );

        PreparedStatement statement =
                mock(
                        PreparedStatement.class
                );

        when(
                connection.prepareStatement(
                        anyString()
                )
        ).thenReturn(
                statement
        );

        when(
                statement.executeQuery()
        ).thenThrow(
                new SQLException(
                        "ambiguous acquire"
                )
        );

        doThrow(
                new SQLException(
                        "abort failed"
                )
        ).when(
                connection
        ).abort(
                any(
                        Executor.class
                )
        );

        assertThatThrownBy(() ->
                PostgresAdvisoryLockExecutor.tryExecute(
                        connection,
                        42L,
                        "test lock",
                        () -> "never"
                )
        )
                .isInstanceOf(
                        DataAccessResourceFailureException.class
                );

        verify(
                connection
        ).close();
    }
}
