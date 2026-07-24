package ru.safeai.gateway.auth.validation;

import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;

public final class Utf8ByteLengthValidator
        implements ConstraintValidator<
        Utf8ByteLength,
        CharSequence
        > {

    private int maxBytes;

    @Override
    public void initialize(
            Utf8ByteLength constraint
    ) {
        int configuredMax = constraint.max();

        if (configuredMax < 0) {
            throw new ConstraintDeclarationException(
                    "Utf8ByteLength.max не может быть отрицательным"
            );
        }

        maxBytes = configuredMax;
    }

    /**
     * Null считается допустимым.
     *
     * <p>Для запрета null поле необходимо дополнительно пометить
     * через {@code @NotNull} или {@code @NotBlank}.</p>
     */
    @Override
    public boolean isValid(
            @Nullable CharSequence value,
            ConstraintValidatorContext context
    ) {
        if (value == null) {
            return true;
        }

        int actualBytes = value
                .toString()
                .getBytes(StandardCharsets.UTF_8)
                .length;

        return actualBytes <= maxBytes;
    }
}