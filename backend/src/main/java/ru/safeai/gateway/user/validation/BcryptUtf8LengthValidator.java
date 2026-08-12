package ru.safeai.gateway.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public final class BcryptUtf8LengthValidator
        implements ConstraintValidator<
        BcryptUtf8Length,
        String
        > {

    private int maxBytes;

    @Override
    public void initialize(
            BcryptUtf8Length annotation
    ) {
        this.maxBytes = annotation.max();

        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "max должен быть положительным"
            );
        }
    }

    @Override
    public boolean isValid(
            String value,
            ConstraintValidatorContext context
    ) {
        /*
         * Null проверяется другими constraints,
         * например @NotBlank.
         */
        if (value == null) {
            return true;
        }

        return value
                .getBytes(
                        StandardCharsets.UTF_8
                )
                .length
                <= maxBytes;
    }
}