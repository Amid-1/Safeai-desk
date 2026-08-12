package ru.safeai.gateway.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(
        validatedBy =
                BcryptUtf8LengthValidator.class
)
@Target({
        ElementType.FIELD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface BcryptUtf8Length {

    int max()
            default PasswordPolicy.MAX_BCRYPT_BYTES;

    String message()
            default PasswordPolicy.BCRYPT_LENGTH_MESSAGE;

    Class<?>[] groups()
            default {};

    Class<? extends Payload>[] payload()
            default {};
}