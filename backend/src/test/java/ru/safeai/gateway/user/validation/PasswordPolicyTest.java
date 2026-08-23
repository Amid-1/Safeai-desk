package ru.safeai.gateway.user.validation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void acceptsStrongPassword() {
        assertThat(
                PasswordPolicy.isValidNewPassword(
                        "Strong_User_123!"
                )
        ).isTrue();
    }

    @Test
    void rejectsNullBlankAndTooShortPasswords() {
        assertThat(
                PasswordPolicy.isValidNewPassword(null)
        ).isFalse();
        assertThat(
                PasswordPolicy.isValidNewPassword("   ")
        ).isFalse();
        assertThat(
                PasswordPolicy.isValidNewPassword("Aa1!short")
        ).isFalse();
    }

    @Test
    void requiresLowercaseUppercaseDigitAndSpecial() {
        assertThat(
                PasswordPolicy.isValidNewPassword(
                        "STRONG_USER_123!"
                )
        ).isFalse();
        assertThat(
                PasswordPolicy.isValidNewPassword(
                        "strong_user_123!"
                )
        ).isFalse();
        assertThat(
                PasswordPolicy.isValidNewPassword(
                        "Strong_User_Test!"
                )
        ).isFalse();
        assertThat(
                PasswordPolicy.isValidNewPassword(
                        "StrongUser12345"
                )
        ).isFalse();
    }

    @Test
    void rejectsIsoControlCharacters() {
        assertThat(
                PasswordPolicy.isValidNewPassword(
                        "Strong_User_123!\n"
                )
        ).isFalse();
    }

    @Test
    void countsUnicodeCodePointsForMinimumLength() {
        String password =
                "Aa1!" + "🙂".repeat(8);

        assertThat(
                password.codePointCount(
                        0,
                        password.length()
                )
        ).isEqualTo(12);

        assertThat(
                PasswordPolicy.isValidNewPassword(password)
        ).isTrue();
    }

    @Test
    void rejectsPasswordOverBcryptUtf8ByteLimit() {
        String password =
                "Aa1!" + "🙂".repeat(18);

        assertThat(
                password.getBytes(StandardCharsets.UTF_8).length
        ).isGreaterThan(
                PasswordPolicy.MAX_BCRYPT_BYTES
        );

        assertThat(
                PasswordPolicy.isValidNewPassword(password)
        ).isFalse();
    }

    @Test
    void utf8LengthUsesBytesNotUtf16Units() {
        String value = "🙂";

        assertThat(value.length()).isEqualTo(2);
        assertThat(PasswordPolicy.utf8Length(value))
                .isEqualTo(4);
    }
}
