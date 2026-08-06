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
        validatorFactory = Validation
                .buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsExactlyOneRole() {
        CreateUserRequest request = requestWithRoles(
                Set.of("USER")
        );

        assertThat(validator.validate(request))
                .isEmpty();
    }

    @Test
    void rejectsNullRoles() {
        CreateUserRequest request = requestWithRoles(null);

        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(
                                violation
                                        .getPropertyPath()
                                        .toString()
                        ).isEqualTo("roles")
                );
    }

    @Test
    void rejectsEmptyRoles() {
        CreateUserRequest request = requestWithRoles(
                Set.of()
        );

        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(
                                violation
                                        .getPropertyPath()
                                        .toString()
                        ).isEqualTo("roles")
                );
    }

    @Test
    void rejectsMoreThanOneRole() {
        CreateUserRequest request = requestWithRoles(
                Set.of("USER", "ADMIN")
        );

        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(
                                violation
                                        .getPropertyPath()
                                        .toString()
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
