package ru.safeai.gateway.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class PasswordValidator
        implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(
            String password,
            ConstraintValidatorContext context
    ) {
        /*
         * Null/blank проверяет @NotBlank.
         *
         * Такой подход предотвращает два validation message
         * на одно отсутствующее поле.
         */
        if (password == null || password.isBlank()) {
            return true;
        }

        return PasswordPolicy
                .isValidNewPassword(password);
    }
}