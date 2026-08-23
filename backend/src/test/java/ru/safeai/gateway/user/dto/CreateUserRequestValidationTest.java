package ru.safeai.gateway.user.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreateUserRequestValidationTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final String VALID_PASSWORD =
            "Strong_User_123!";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory =
                Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsExactlyOneRole() {
        assertThat(
                validator.validate(
                        requestWithRoles(
                                Set.of("USER")
                        )
                )
        ).isEmpty();
    }

    @Test
    void rejectsNullRoles() {
        assertRolesViolation(
                requestWithRoles(null)
        );
    }

    @Test
    void rejectsEmptyRoles() {
        assertRolesViolation(
                requestWithRoles(Set.of())
        );
    }

    @Test
    void rejectsMoreThanOneRole() {
        assertRolesViolation(
                requestWithRoles(
                        Set.of("USER", "ADMIN")
                )
        );
    }

    @Test
    void rejectsWeakPassword() {
        CreateUserRequest request =
                new CreateUserRequest(
                        ORGANIZATION_ID,
                        "user@example.com",
                        "weak",
                        "Test User",
                        Set.of("USER")
                );

        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(
                                violation.getPropertyPath().toString()
                        ).isEqualTo("password")
                );
    }

    @Test
    void rejectsInvalidEmail() {
        CreateUserRequest request =
                new CreateUserRequest(
                        ORGANIZATION_ID,
                        "not-an-email",
                        VALID_PASSWORD,
                        "Test User",
                        Set.of("USER")
                );

        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(
                                violation.getPropertyPath().toString()
                        ).isEqualTo("email")
                );
    }

    private void assertRolesViolation(
            CreateUserRequest request
    ) {
        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(
                                violation.getPropertyPath().toString()
                        ).isEqualTo("roles")
                );
    }

    private CreateUserRequest requestWithRoles(
            Set<String> roles
    ) {
        return new CreateUserRequest(
                ORGANIZATION_ID,
                "user@example.com",
                VALID_PASSWORD,
                "Test User",
                roles
        );
    }
}
