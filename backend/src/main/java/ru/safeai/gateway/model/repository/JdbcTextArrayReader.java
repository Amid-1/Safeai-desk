package ru.safeai.gateway.model.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Reads SQL text arrays without depending on a driver's Java array class. */
final class JdbcTextArrayReader {

    private JdbcTextArrayReader() {
    }

    static List<String> read(Array array, String columnName) throws SQLException {
        if (array == null) {
            return List.of();
        }

        Throwable failure = null;
        try {
            switch (array.getBaseType()) {
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                     Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> {
                    // PostgreSQL text[] has the JDBC base type VARCHAR.
                }
                default -> throw new SQLException("Expected SQL text[] for " + columnName);
            }

            List<String> values = new ArrayList<>();
            try (ResultSet elements = array.getResultSet()) {
                while (elements.next()) {
                    String value = elements.getObject(2, String.class);
                    if (value == null) {
                        throw new SQLException("NULL element in " + columnName);
                    }
                    values.add(value);
                }
            }
            return List.copyOf(values);
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                array.free();
            } catch (SQLException cleanupFailure) {
                if (failure == null) {
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
