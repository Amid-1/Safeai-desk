package ru.safeai.gateway.auth.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfTokenResponseTest {

    @Test
    void hidesTokenFromDiagnosticsWithoutChangingItsAccessor() {
        String token = "csrf-secret-value";
        CsrfTokenResponse response = new CsrfTokenResponse("X-XSRF-TOKEN", "_csrf", token);

        assertThat(response.toString()).contains("<redacted>").doesNotContain(token);
        assertThat(response.token()).isEqualTo(token);
    }
}
