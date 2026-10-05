package ru.safeai.gateway.model.repository;

import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcTextArrayReaderTest {

    @Test
    void sqlNullArrayIsAnEmptyList() throws Exception {
        assertThat(JdbcTextArrayReader.read(null, "keys")).isEmpty();
    }

    @Test
    void readsOrderedTextElementsAndReleasesBothResources() throws Exception {
        Array array = mock(Array.class);
        ResultSet elements = mock(ResultSet.class);
        when(array.getBaseType()).thenReturn(Types.VARCHAR);
        when(array.getResultSet()).thenReturn(elements);
        when(elements.next()).thenReturn(true, true, false);
        when(elements.getObject(2, String.class)).thenReturn("b:model", "a:model");

        assertThat(JdbcTextArrayReader.read(array, "keys")).containsExactly("b:model", "a:model");
        verify(elements).close();
        verify(array).free();
        verify(array, never()).getArray();
    }

    @Test
    void emptyArrayIsReleased() throws Exception {
        Array array = mock(Array.class);
        ResultSet elements = mock(ResultSet.class);
        when(array.getBaseType()).thenReturn(Types.VARCHAR);
        when(array.getResultSet()).thenReturn(elements);
        when(elements.next()).thenReturn(false);

        assertThat(JdbcTextArrayReader.read(array, "keys")).isEmpty();
        verify(elements).close();
        verify(array).free();
    }

    @Test
    void nonTextArrayIsRejectedAndReleased() throws Exception {
        Array array = mock(Array.class);
        when(array.getBaseType()).thenReturn(Types.INTEGER);
        assertThatThrownBy(() -> JdbcTextArrayReader.read(array, "keys"))
                .isInstanceOf(SQLException.class).hasMessageContaining("text[]");
        verify(array).free();
        verify(array, never()).getResultSet();
    }

    @Test
    void nullElementIsRejectedAndResourcesAreReleased() throws Exception {
        Array array = mock(Array.class);
        ResultSet elements = mock(ResultSet.class);
        when(array.getBaseType()).thenReturn(Types.VARCHAR);
        when(array.getResultSet()).thenReturn(elements);
        when(elements.next()).thenReturn(true);
        when(elements.getObject(2, String.class)).thenReturn(null);
        assertThatThrownBy(() -> JdbcTextArrayReader.read(array, "keys"))
                .isInstanceOf(SQLException.class).hasMessageContaining("NULL element");
        verify(elements).close();
        verify(array).free();
    }

    @Test
    void readFailureIsNotHiddenByCleanupFailure() throws Exception {
        Array array = mock(Array.class);
        ResultSet elements = mock(ResultSet.class);
        SQLException reading = new SQLException("read failed");
        SQLException cleanup = new SQLException("free failed");
        when(array.getBaseType()).thenReturn(Types.VARCHAR);
        when(array.getResultSet()).thenReturn(elements);
        when(elements.next()).thenThrow(reading);
        doThrow(cleanup).when(array).free();

        assertThatThrownBy(() -> JdbcTextArrayReader.read(array, "keys"))
                .isSameAs(reading).hasSuppressedException(cleanup);
        verify(elements).close();
        verify(array).free();
    }

    @Test
    void cleanupFailureAfterSuccessfulReadIsReported() throws Exception {
        Array array = mock(Array.class);
        ResultSet elements = mock(ResultSet.class);
        SQLException cleanup = new SQLException("free failed");
        when(array.getBaseType()).thenReturn(Types.VARCHAR);
        when(array.getResultSet()).thenReturn(elements);
        doThrow(cleanup).when(array).free();
        assertThatThrownBy(() -> JdbcTextArrayReader.read(array, "keys")).isSameAs(cleanup);
        verify(elements).close();
    }
}
