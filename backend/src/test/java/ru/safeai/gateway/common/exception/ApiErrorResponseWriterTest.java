package ru.safeai.gateway.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.safeai.gateway.common.security.RequestIdFilter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorResponseWriterTest {

    private static final String REQUEST_ID =
            "server-request-id";

    private static final String ALLOWED_ORIGIN =
            "https://app.example.com";

    private static final Instant FIXED_NOW =
            Instant.parse(
                    "2026-10-08T00:00:00Z"
            );

    @Test
    void securityErrorDropsUnsafeInheritedHeadersButKeepsSafeHeaders()
            throws Exception {

        MockHttpServletResponse response =
                writeSecurityError();

        assertThat(
                response.getHeader(
                        RequestIdFilter.REQUEST_ID_HEADER
                )
        ).isEqualTo(
                REQUEST_ID
        );

        assertThat(
                response.getHeader(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN
                )
        ).isEqualTo(
                ALLOWED_ORIGIN
        );

        assertThat(
                response.getHeader(
                        HttpHeaders.LOCATION
                )
        ).isNull();

        assertThat(
                response.getHeader(
                        HttpHeaders.SET_COOKIE
                )
        ).isNull();

        assertThat(
                response.getHeader(
                        HttpHeaders.ETAG
                )
        ).isNull();

        assertThat(
                response.getHeader(
                        HttpHeaders.CACHE_CONTROL
                )
        ).isEqualTo(
                "no-store"
        );
    }

    private static MockHttpServletResponse writeSecurityError()
            throws Exception {

        MockHttpServletRequest request =
                requestWithServerRequestId();

        MockHttpServletResponse response =
                responseWithInheritedHeaders();

        writer().write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.UNAUTHORIZED,
                "Требуется авторизация"
        );

        return response;
    }

    private static MockHttpServletRequest requestWithServerRequestId() {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.setRequestURI(
                "/api/test"
        );

        request.setAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                REQUEST_ID
        );

        return request;
    }

    private static MockHttpServletResponse responseWithInheritedHeaders() {
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        response.setHeader(
                RequestIdFilter.REQUEST_ID_HEADER,
                REQUEST_ID
        );

        response.setHeader(
                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                ALLOWED_ORIGIN
        );

        response.setHeader(
                HttpHeaders.LOCATION,
                "/must-not-survive"
        );

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                "unsafe=1"
        );

        response.setHeader(
                HttpHeaders.ETAG,
                "\"old\""
        );

        return response;
    }

    private static ApiErrorResponseWriter writer() {
        return new ApiErrorResponseWriter(
                JsonMapper.builder()
                        .build(),
                new ApiErrorResponseFactory(
                        Clock.fixed(
                                FIXED_NOW,
                                ZoneOffset.UTC
                        )
                )
        );
    }
}
