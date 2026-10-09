package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.safeai.gateway.common.security.RequestIdFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthAuditRequestSupportTest {

    @Test
    void usesServerRequestIdAndKeepsClientIdSeparate() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                "server-id"
        );

        request.setAttribute(
                RequestIdFilter.CLIENT_REQUEST_ID_ATTRIBUTE,
                "client-id"
        );

        assertThat(
                AuthAuditRequestSupport
                        .requestIdOrUnknown(
                                request
                        )
        ).isEqualTo(
                "server-id"
        );

        assertThat(
                AuthAuditRequestSupport
                        .clientRequestIdOrNull(
                                request
                        )
        ).isEqualTo(
                "client-id"
        );
    }

    @Test
    void truncatesByCodePointsWithoutSplittingSupplementaryCharacter() {
        String value =
                "A😀B";

        assertThat(
                AuthAuditRequestSupport
                        .truncateNullable(
                                value,
                                2
                        )
        ).isEqualTo(
                "A😀"
        );
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() ->
                AuthAuditRequestSupport
                        .truncateNullable(
                                "value",
                                0
                        )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "положительным"
                );
    }
}
