package ru.safeai.gateway.common.persistence;

import org.hibernate.exception.ConstraintViolationException;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DatabaseConstraintClassifier {

    private static final String UNIQUE_VIOLATION =
            "23505";

    private static final String FOREIGN_KEY_VIOLATION =
            "23503";

    private DatabaseConstraintClassifier() {
    }

    public static boolean isUniqueViolation(
            Throwable throwable,
            String... acceptedConstraintNames
    ) {
        if (!hasSqlState(
                throwable,
                UNIQUE_VIOLATION
        )) {
            return false;
        }

        Set<String> accepted =
                normalizeConstraintNames(
                        acceptedConstraintNames
                );

        if (accepted.isEmpty()) {
            return false;
        }

        String actualConstraint =
                findConstraintName(
                        throwable
                );

        return actualConstraint != null
                && accepted.contains(
                        actualConstraint
                );
    }

    public static boolean isForeignKeyViolation(
            Throwable throwable
    ) {
        return hasSqlState(
                throwable,
                FOREIGN_KEY_VIOLATION
        );
    }

    private static Set<String> normalizeConstraintNames(
            String... constraintNames
    ) {
        if (constraintNames == null) {
            throw new IllegalArgumentException(
                    "acceptedConstraintNames не должен быть null"
            );
        }

        Set<String> result =
                new LinkedHashSet<>();

        for (String constraintName
                : constraintNames) {

            if (constraintName == null
                    || constraintName.isBlank()) {

                throw new IllegalArgumentException(
                        "acceptedConstraintNames "
                                + "не должен содержать null/blank"
                );
            }

            String normalized =
                    constraintName.trim();

            if (!normalized.equals(
                    constraintName
            )) {
                throw new IllegalArgumentException(
                        "Имя constraint не должно содержать "
                                + "внешние пробелы: "
                                + constraintName
                );
            }

            /*
             * Duplicates are harmless for an internal classifier and should
             * not make Set.of(...) throw an unrelated exception.
             */
            result.add(
                    normalized
            );
        }

        return Set.copyOf(
                result
        );
    }

    private static boolean hasSqlState(
            Throwable throwable,
            String expectedSqlState
    ) {
        for (Throwable current
                : throwableChain(
                throwable
        )) {
            if (current
                    instanceof SQLException sqlException
                    && expectedSqlState.equals(
                    sqlException.getSQLState()
            )) {
                return true;
            }

            if (current
                    instanceof ConstraintViolationException violation
                    && expectedSqlState.equals(
                    violation.getSQLState()
            )) {
                return true;
            }
        }

        return false;
    }

    private static String findConstraintName(
            Throwable throwable
    ) {
        for (Throwable current
                : throwableChain(
                throwable
        )) {

            if (current
                    instanceof ConstraintViolationException violation) {

                String constraintName =
                        violation.getConstraintName();

                if (constraintName != null
                        && !constraintName.isBlank()) {

                    return constraintName;
                }
            }

            if (current
                    instanceof SQLException sqlException) {

                String constraintName =
                        findPostgresConstraintName(
                                sqlException
                        );

                if (constraintName != null) {
                    return constraintName;
                }
            }
        }

        return null;
    }

    /**
     * PostgreSQL's constraint name is driver-specific and is not exposed by
     * java.sql.SQLException. The JDBC driver is intentionally runtime-scoped
     * in this project, so this helper reads PSQLException's
     * ServerErrorMessage reflectively instead of introducing a compile-time
     * dependency from common.persistence to the PostgreSQL driver.
     */
    private static String findPostgresConstraintName(
            SQLException exception
    ) {
        try {
            Method getServerErrorMessage =
                    exception.getClass()
                            .getMethod(
                                    "getServerErrorMessage"
                            );

            Object serverErrorMessage =
                    getServerErrorMessage.invoke(
                            exception
                    );

            if (serverErrorMessage == null) {
                return null;
            }

            Method getConstraint =
                    serverErrorMessage.getClass()
                            .getMethod(
                                    "getConstraint"
                            );

            Object value =
                    getConstraint.invoke(
                            serverErrorMessage
                    );

            if (value
                    instanceof String constraintName
                    && !constraintName.isBlank()) {

                return constraintName;
            }
        } catch (ReflectiveOperationException
                 | SecurityException ignored) {
            /*
             * Not a PostgreSQL server exception (or a driver version that does
             * not expose this metadata). Hibernate extraction above remains
             * available, and SQLSTATE classification still works.
             */
        }

        return null;
    }

    /**
     * Walks both ordinary causes and SQLException.nextException chains while
     * protecting against cycles.
     */
    private static List<Throwable> throwableChain(
            Throwable throwable
    ) {
        if (throwable == null) {
            return List.of();
        }

        List<Throwable> chain =
                new ArrayList<>();

        Set<Throwable> seen =
                Collections.newSetFromMap(
                        new IdentityHashMap<>()
                );

        Deque<Throwable> pending =
                new ArrayDeque<>();

        pending.add(
                throwable
        );

        while (!pending.isEmpty()) {
            Throwable current =
                    pending.removeFirst();

            if (!seen.add(
                    current
            )) {
                continue;
            }

            chain.add(
                    current
            );

            Throwable cause =
                    current.getCause();

            if (cause != null) {
                pending.addLast(
                        cause
                );
            }

            if (current
                    instanceof SQLException sqlException) {

                SQLException next =
                        sqlException.getNextException();

                if (next != null) {
                    pending.addLast(
                            next
                    );
                }
            }
        }

        return List.copyOf(
                chain
        );
    }
}
