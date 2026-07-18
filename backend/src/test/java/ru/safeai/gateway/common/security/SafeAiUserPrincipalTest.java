package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeAiUserPrincipalTest {

    @Test
    void constructor_shouldRejectBlankEmail() {
        assertThatThrownBy(() -> new SafeAiUserPrincipal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                " ",
                "",
                true,
                0,
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void constructor_shouldRejectEmailWithExternalSpaces() {
        assertThatThrownBy(() -> new SafeAiUserPrincipal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                " admin@test.com ",
                "",
                true,
                0,
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пробелы");
    }

    @Test
    void constructor_shouldRejectNegativeTokenVersion() {
        assertThatThrownBy(() -> new SafeAiUserPrincipal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "admin@test.com",
                "",
                true,
                -1,
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenVersion");
    }
}
