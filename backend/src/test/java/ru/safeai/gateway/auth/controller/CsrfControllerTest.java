package ru.safeai.gateway.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import ru.safeai.gateway.auth.dto.CsrfTokenResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfControllerTest {

    private final CsrfController controller =
            new CsrfController();

    @Test
    void csrfReturnsTokenMetadataAndDisablesCaching() {
        DefaultCsrfToken token = new DefaultCsrfToken(
                "X-XSRF-TOKEN",
                "_csrf",
                "csrf-value"
        );

        ResponseEntity<CsrfTokenResponse> response =
                controller.csrf(token);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .isTrue();
        assertThat(response.getHeaders().getCacheControl())
                .contains("no-store");
        assertThat(response.getBody())
                .isEqualTo(new CsrfTokenResponse(
                        "X-XSRF-TOKEN",
                        "_csrf",
                        "csrf-value"
                ));
    }
}
