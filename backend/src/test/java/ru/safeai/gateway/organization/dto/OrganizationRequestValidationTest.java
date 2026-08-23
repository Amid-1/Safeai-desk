package ru.safeai.gateway.organization.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationRequestValidationTest {

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
    void createRequiresNonBlankNameWithin255Characters() {
        assertThat(
                validator.validate(
                        new CreateOrganizationRequest(
                                "Tenant"
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new CreateOrganizationRequest(
                        "   "
                ),
                "name"
        );

        assertHasViolation(
                new CreateOrganizationRequest(
                        "a".repeat(256)
                ),
                "name"
        );
    }

    /**
     * Negative expectedVersion is created intentionally.
     *
     * <p>The DTO is a Bean Validation carrier, therefore this test must
     * be able to construct an invalid value and then verify that
     * Jakarta Validator rejects it.</p>
     */
    @SuppressWarnings("DataFlowIssue")
    @Test
    void updateNameRequiresExpectedVersionAndValidName() {
        assertThat(
                validator.validate(
                        new UpdateOrganizationRequest(
                                "Tenant",
                                0L
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new UpdateOrganizationRequest(
                        "Tenant",
                        null
                ),
                "expectedVersion"
        );

        assertHasViolation(
                new UpdateOrganizationRequest(
                        "Tenant",
                        -1L
                ),
                "expectedVersion"
        );

        assertHasViolation(
                new UpdateOrganizationRequest(
                        "   ",
                        0L
                ),
                "name"
        );

        assertHasViolation(
                new UpdateOrganizationRequest(
                        "a".repeat(256),
                        0L
                ),
                "name"
        );
    }

    /**
     * Invalid values are intentional and verify the validation
     * boundary of the destructive disable request.
     */
    @SuppressWarnings("DataFlowIssue")
    @Test
    void disableRequiresConfirmationAndExpectedVersion() {
        assertThat(
                validator.validate(
                        new DisableOrganizationRequest(
                                0L,
                                "Tenant"
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new DisableOrganizationRequest(
                        0L,
                        "   "
                ),
                "confirmationName"
        );

        assertHasViolation(
                new DisableOrganizationRequest(
                        null,
                        "Tenant"
                ),
                "expectedVersion"
        );

        assertHasViolation(
                new DisableOrganizationRequest(
                        -1L,
                        "Tenant"
                ),
                "expectedVersion"
        );
    }

    /**
     * Negative expectedVersion is intentional so that
     * @PositiveOrZero is actually exercised by the test.
     */
    @SuppressWarnings("DataFlowIssue")
    @Test
    void enableRequiresNonNegativeExpectedVersion() {
        assertThat(
                validator.validate(
                        new EnableOrganizationRequest(
                                0L
                        )
                )
        ).isEmpty();

        assertHasViolation(
                new EnableOrganizationRequest(
                        null
                ),
                "expectedVersion"
        );

        assertHasViolation(
                new EnableOrganizationRequest(
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