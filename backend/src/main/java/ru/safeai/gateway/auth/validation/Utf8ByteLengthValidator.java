package ru.safeai.gateway.auth.validation;

import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;
import ru.safeai.gateway.common.validation.UnicodeValidationSupport;

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

        String text = value.toString();
        try {
            UnicodeValidationSupport.requireWellFormedUtf16(text, "value");
        } catch (IllegalArgumentException invalidUnicode) {
            return false;
        }

        return text.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }
}
