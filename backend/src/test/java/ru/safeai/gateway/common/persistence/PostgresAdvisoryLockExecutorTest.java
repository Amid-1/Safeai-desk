package ru.safeai.gateway.common.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    void abortFailureClosesDriverConnectionBeforePoolHandle() throws Exception {
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

        Connection driverConnection = mock(Connection.class);
        when(connection.unwrap(Connection.class)).thenReturn(driverConnection);

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

        var order = inOrder(driverConnection, connection);
        order.verify(driverConnection).close();
        order.verify(connection).close();
    }

    @Test
    void missingDriverConnectionDoesNotMaskAcquireFailureOrSkipHandleClose() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        SQLException acquireFailure = new SQLException("ambiguous acquire");
        SQLException abortFailure = new SQLException("abort failed");
        SQLException closeFailure = new SQLException("pool handle close failed");

        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(acquireFailure);
        when(connection.unwrap(Connection.class)).thenReturn(null);
        doThrow(abortFailure).when(connection).abort(any(Executor.class));
        doThrow(closeFailure).when(connection).close();

        assertThatThrownBy(() -> PostgresAdvisoryLockExecutor.tryExecute(
                connection, 42L, "test lock", () -> {
                    throw new AssertionError("Action must not run without a confirmed lock");
                }))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasCause(acquireFailure);
        assertThat(acquireFailure.getSuppressed()).containsExactly(abortFailure, closeFailure);

        var order = inOrder(connection);
        order.verify(connection).abort(any(Executor.class));
        order.verify(connection).unwrap(Connection.class);
        order.verify(connection).close();
    }
}
