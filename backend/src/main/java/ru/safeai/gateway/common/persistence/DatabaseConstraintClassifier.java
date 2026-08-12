package ru.safeai.gateway.common.persistence;

import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
                Set.of(
                        acceptedConstraintNames
                );

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

    private static boolean hasSqlState(
            Throwable throwable,
            String expectedSqlState
    ) {
        for (Throwable current
                : causeChain(throwable)) {

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
                : causeChain(throwable)) {

            if (current
                    instanceof ConstraintViolationException violation) {
                return violation
                        .getConstraintName();
            }
        }

        return null;
    }

    private static List<Throwable> causeChain(
            Throwable throwable
    ) {
        List<Throwable> chain =
                new ArrayList<>();

        Set<Throwable> seen =
                Collections.newSetFromMap(
                        new IdentityHashMap<>()
                );

        Throwable current = throwable;

        while (current != null
                && seen.add(current)) {
            chain.add(current);
            current = current.getCause();
        }

        return List.copyOf(chain);
    }
}