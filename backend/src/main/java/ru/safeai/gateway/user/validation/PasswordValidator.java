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
        if (password == null || password.isBlank()) {
            return false;
        }

        if (password.codePointCount(0, password.length())
                < PasswordPolicy.MIN_LENGTH) {
            return false;
        }

        if (!PasswordPolicy.hasValidUtf8Length(password)) {
            return false;
        }

        boolean hasLowercase = false;
        boolean hasUppercase = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (int offset = 0; offset < password.length();) {
            int codePoint = password.codePointAt(offset);

            if (Character.isISOControl(codePoint)) {
                return false;
            }

            if (Character.isLowerCase(codePoint)) {
                hasLowercase = true;
            } else if (Character.isUpperCase(codePoint)) {
                hasUppercase = true;
            } else if (Character.isDigit(codePoint)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }

            offset += Character.charCount(codePoint);
        }

        return hasLowercase
                && hasUppercase
                && hasDigit
                && hasSpecial;
    }
}
