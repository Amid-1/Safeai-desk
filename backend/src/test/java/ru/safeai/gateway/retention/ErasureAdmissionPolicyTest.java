// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/retention/ErasureAdmissionPolicyTest.java
package ru.safeai.gateway.retention;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.common.exception.ConflictException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ErasureAdmissionPolicyTest {
    // These are expected JdbcTemplate arguments in mock-based tests. They do not need
    // an IDE-connected database; actual schema verification belongs to integration tests.
    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    private static final String CHAT_QUERY = "SELECT chat_allowed FROM public.platform_erasure_policy_v102 WHERE singleton";
    @SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
    private static final String KNOWLEDGE_QUERY = "SELECT knowledge_allowed FROM public.platform_erasure_policy_v102 WHERE singleton";
    private static final String SHARE = " FOR SHARE";

    @Test
    void acceptanceUsesSharedTransactionLockAndFailsClosed() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String query = CHAT_QUERY + SHARE;
        when(jdbc.queryForObject(query, Boolean.class)).thenReturn(false, true);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT)))
                .isInstanceOf(ConflictException.class);
        tx.executeWithoutResult(status -> ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT));
        verify(jdbc, times(2)).queryForObject(query, Boolean.class);
        verify(connection).rollback();
        verify(connection).commit();
    }

    @Test
    void refusesAdmissionOutsideTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        assertThatThrownBy(() -> ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT))
                .isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class));
    }

    @Test
    void capabilityUsesKnowledgeScopeWithoutWriteLockAndFailsClosedOnNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(KNOWLEDGE_QUERY, Boolean.class)).thenReturn(true, (Boolean) null);
        assertThat(ErasureAdmissionPolicy.allowed(jdbc, ErasureAdmissionPolicy.Scope.KNOWLEDGE)).isTrue();
        assertThat(ErasureAdmissionPolicy.allowed(jdbc, ErasureAdmissionPolicy.Scope.KNOWLEDGE)).isFalse();
        verify(jdbc, times(2)).queryForObject(KNOWLEDGE_QUERY, Boolean.class);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void chatCapabilityDoesNotAcquireWriteLock() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(CHAT_QUERY, Boolean.class)).thenReturn(false, true);
        assertThat(ErasureAdmissionPolicy.allowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT)).isFalse();
        assertThat(ErasureAdmissionPolicy.allowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT)).isTrue();
        verify(jdbc, times(2)).queryForObject(CHAT_QUERY, Boolean.class);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void knowledgeAdmissionUsesItsOwnPolicyAndShareLock() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate tx = transaction(dataSource);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(jdbc.queryForObject(KNOWLEDGE_QUERY + SHARE, Boolean.class)).thenReturn(true, false, null);

        tx.executeWithoutResult(status -> ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.KNOWLEDGE));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.KNOWLEDGE)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.KNOWLEDGE)))
                .isInstanceOf(ConflictException.class);
        verify(jdbc, times(3)).queryForObject(KNOWLEDGE_QUERY + SHARE, Boolean.class);
        verify(jdbc, never()).queryForObject(startsWith(CHAT_QUERY), eq(Boolean.class));
    }

    @Test
    void refusesAdmissionInReadOnlyTransaction() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate tx = transaction(dataSource);
        tx.setReadOnly(true);
        when(jdbc.getDataSource()).thenReturn(dataSource);

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT)))
                .isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class));
    }

    @Test
    void refusesTransactionBoundToDifferentDatasource() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource transactionSource = mock(DataSource.class);
        DataSource policySource = mock(DataSource.class);
        TransactionTemplate tx = transaction(transactionSource);
        when(jdbc.getDataSource()).thenReturn(policySource);

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT)))
                .isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class));
    }

    @Test
    void refusesAdmissionWithoutDatasource() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT))
                .isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class));
    }

    @Test
    void missingPolicyRowAndDatabaseFailureNeverGrantAdmission() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        TransactionTemplate tx = transaction(dataSource);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        var missingRow = new EmptyResultDataAccessException(1);
        var unavailable = new DataAccessResourceFailureException("policy database unavailable");
        when(jdbc.queryForObject(CHAT_QUERY + SHARE, Boolean.class)).thenThrow(missingRow, unavailable);

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT)))
                .isSameAs(missingRow);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                ErasureAdmissionPolicy.requireAllowed(jdbc, ErasureAdmissionPolicy.Scope.CHAT)))
                .isSameAs(unavailable);
        verify(jdbc, times(2)).queryForObject(CHAT_QUERY + SHARE, Boolean.class);
    }

    @Test
    void nullScopeNeverFallsBackToKnowledgePolicy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> ErasureAdmissionPolicy.allowed(jdbc, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(jdbc);
    }

    private static TransactionTemplate transaction(DataSource dataSource) throws SQLException {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
