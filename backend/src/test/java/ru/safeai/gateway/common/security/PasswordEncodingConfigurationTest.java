package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncodingConfigurationTest {

    private static final String PASSWORD =
            "correct horse battery staple";

    private final PasswordEncoder encoder =
            new PasswordEncodingConfiguration()
                    .passwordEncoder();

    @Test
    void newHashesUseBcryptPrefixAndStrengthTwelve() {
        String hash =
                encoder.encode(PASSWORD);

        assertThat(hash)
                .startsWith(
                        "{bcrypt}$2"
                )
                .matches(
                        "\\{bcrypt}\\$2[aby]\\$12\\$.*"
                );

        assertThat(
                encoder.matches(
                        PASSWORD,
                        hash
                )
        ).isTrue();
    }

    @Test
    void legacyUnprefixedBcryptHashStillMatchesDuringMigration() {
        String legacyHash =
                new BCryptPasswordEncoder()
                        .encode(
                                "password"
                        );

        assertThat(legacyHash)
                .doesNotStartWith(
                        "{bcrypt}"
                );

        assertThat(
                encoder.matches(
                        "password",
                        legacyHash
                )
        ).isTrue();
    }

    @Test
    void incorrectPasswordDoesNotMatch() {
        String hash =
                encoder.encode(PASSWORD);

        assertThat(
                encoder.matches(
                        "incorrect password",
                        hash
                )
        ).isFalse();
    }
}
