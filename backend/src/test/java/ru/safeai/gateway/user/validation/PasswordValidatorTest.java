package ru.safeai.gateway.user.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordValidatorTest {

    private final PasswordValidator validator =
            new PasswordValidator();

    @Test
    void acceptsStrongPassword() {
        assertThat(validator.isValid(
                "Strong_Password_123!",
                null
        )).isTrue();
    }

    @Test
    void acceptsSpaceAsAValidSpecialCharacter() {
        assertThat(validator.isValid(
                "Strong Password 123",
                null
        )).isTrue();
    }

    @Test
    void rejectsNullBlankAndShortPasswords() {
        assertThat(validator.isValid(null, null)).isFalse();
        assertThat(validator.isValid("   ", null)).isFalse();
        assertThat(validator.isValid("Aa1!", null)).isFalse();
    }

    @Test
    void rejectsPasswordWithoutRequiredCharacterGroups() {
        assertThat(validator.isValid("lowercase_123!", null)).isFalse();
        assertThat(validator.isValid("UPPERCASE_123!", null)).isFalse();
        assertThat(validator.isValid("NoDigitsHere!", null)).isFalse();
        assertThat(validator.isValid("NoSpecial1234", null)).isFalse();
    }

    @Test
    void rejectsControlCharacters() {
        assertThat(validator.isValid(
                "Strong123!\nPassword",
                null
        )).isFalse();
    }

    @Test
    void rejectsMoreThan72Utf8Bytes() {
        String password = "Aa1!" + "я".repeat(35);

        assertThat(password.length()).isLessThanOrEqualTo(72);
        assertThat(validator.isValid(password, null)).isFalse();
    }

    @Test
    void countsUnicodeCodePointsForMinimumLength() {
        String password = "Aa1!" + "🙂".repeat(8);

        assertThat(validator.isValid(password, null)).isTrue();
    }
}
