package ru.safeai.gateway.common.persistence;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseConstraintClassifierTest {

    @Test
    void extractsConstraintFromDriverLevelServerErrorMetadata() {
        SQLException exception =
                new TestPostgresException(
                        "23505",
                        "ux_users_email_canonical"
                );

        assertThat(
                DatabaseConstraintClassifier.isUniqueViolation(
                        exception,
                        "ux_users_email_canonical"
                )
        ).isTrue();
    }

    @Test
    void duplicateAcceptedConstraintNamesAreHarmless() {
        SQLException exception =
                new TestPostgresException(
                        "23505",
                        "ux_users_email_canonical"
                );

        assertThat(
                DatabaseConstraintClassifier.isUniqueViolation(
                        exception,
                        "ux_users_email_canonical",
                        "ux_users_email_canonical"
                )
        ).isTrue();
    }

    @Test
    void blankAcceptedConstraintNameFailsClearly() {
        SQLException exception =
                new TestPostgresException(
                        "23505",
                        "ux_users_email_canonical"
                );

        assertThatThrownBy(() ->
                DatabaseConstraintClassifier.isUniqueViolation(
                        exception,
                        " "
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "null/blank"
                );
    }

    public static final class TestPostgresException
            extends SQLException {

        private final TestServerErrorMessage serverErrorMessage;

        public TestPostgresException(
                String sqlState,
                String constraint
        ) {
            super(
                    "duplicate",
                    sqlState
            );

            this.serverErrorMessage =
                    new TestServerErrorMessage(
                            constraint
                    );
        }

        public TestServerErrorMessage getServerErrorMessage() {
            return serverErrorMessage;
        }
    }

    public static final class TestServerErrorMessage {

        private final String constraint;

        public TestServerErrorMessage(
                String constraint
        ) {
            this.constraint =
                    constraint;
        }

        public String getConstraint() {
            return constraint;
        }
    }
}
