package ru.safeai.gateway.auth.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Utf8ByteLengthValidatorTest {

    @Test
    void enforcesByteLengthAndRejectsUnpairedSurrogates() {
        Utf8ByteLength constraint = mock(Utf8ByteLength.class);
        when(constraint.max()).thenReturn(4);

        Utf8ByteLengthValidator validator = new Utf8ByteLengthValidator();
        validator.initialize(constraint);

        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("😀", null)).isTrue();
        assertThat(validator.isValid("😀a", null)).isFalse();
        assertThat(validator.isValid("\uD800", null)).isFalse();
        assertThat(validator.isValid("\uDC00", null)).isFalse();
    }
}
