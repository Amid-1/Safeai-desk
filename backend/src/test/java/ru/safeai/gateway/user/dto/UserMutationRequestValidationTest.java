package ru.safeai.gateway.user.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserMutationRequestValidationTest {

    private static final String VALID_PASSWORD =
            "Strong_User_123!";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory =
                Validation.buildDefaultValidatorFactory();

        validator =
                validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Test
    void updateUserRequiresValidEmailAndExpectedVersion() {
        assertThat(
                validator.validate(
                        new UpdateUserRequest(
                                "user@example.com",
                                "User",
                                0L
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new UpdateUserRequest(
                        "bad-email",
                        "User",
                        0L
                ),
                "email"
        );

        assertHasViolation(
                new UpdateUserRequest(
                        "user@example.com",
                        "User",
                        null
                ),
                "expectedVersion"
        );
    }

    /**
     * Invalid constructor arguments are intentional here:
     * the DTO itself is a Bean Validation carrier and the test verifies
     * that Jakarta Validator rejects those values.
     */
    @SuppressWarnings("DataFlowIssue")
    @Test
    void updateEnabledRequiresFlagAndExpectedVersion() {
        assertThat(
                validator.validate(
                        new UpdateUserEnabledRequest(
                                false,
                                0L
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new UpdateUserEnabledRequest(
                        null,
                        0L
                ),
                "enabled"
        );

        assertHasViolation(
                new UpdateUserEnabledRequest(
                        true,
                        -1L
                ),
                "expectedVersion"
        );
    }

    @Test
    void updateRolesRequiresExactlyOneRoleAndExpectedVersion() {
        assertThat(
                validator.validate(
                        new UpdateUserRolesRequest(
                                Set.of("USER"),
                                0L
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new UpdateUserRolesRequest(
                        Set.of(),
                        0L
                ),
                "roles"
        );

        assertHasViolation(
                new UpdateUserRolesRequest(
                        Set.of(
                                "USER",
                                "ADMIN"
                        ),
                        0L
                ),
                "roles"
        );

        assertHasViolation(
                new UpdateUserRolesRequest(
                        Set.of("USER"),
                        null
                ),
                "expectedVersion"
        );
    }

    @Test
    void resetPasswordRequiresCurrentPasswordPolicyAndExpectedVersion() {
        assertThat(
                validator.validate(
                        new ResetUserPasswordRequest(
                                VALID_PASSWORD,
                                0L
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new ResetUserPasswordRequest(
                        "weak",
                        0L
                ),
                "password"
        );

        assertHasViolation(
                new ResetUserPasswordRequest(
                        VALID_PASSWORD,
                        null
                ),
                "expectedVersion"
        );
    }

    /**
     * Negative expectedVersion is deliberately constructed in order to
     * verify the @PositiveOrZero Bean Validation contract.
     */
    @SuppressWarnings("DataFlowIssue")
    @Test
    void permanentDeleteRequiresValidConfirmationEmailAndExpectedVersion() {
        assertThat(
                validator.validate(
                        new PermanentDeleteUserRequest(
                                "user@example.com",
                                0L
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new PermanentDeleteUserRequest(
                        "not-an-email",
                        0L
                ),
                "confirmationEmail"
        );

        assertHasViolation(
                new PermanentDeleteUserRequest(
                        "user@example.com",
                        -1L
                ),
                "expectedVersion"
        );
    }

    private void assertHasViolation(
            Object request,
            String property
    ) {
        assertThat(
                validator.validate(request)
        ).anySatisfy(violation ->
                assertThat(
                        violation
                                .getPropertyPath()
                                .toString()
                ).isEqualTo(
                        property
                )
        );
    }
}