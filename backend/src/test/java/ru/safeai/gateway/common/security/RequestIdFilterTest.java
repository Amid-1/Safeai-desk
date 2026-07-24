package ru.safeai.gateway.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private static final String PREVIOUS_MDC_VALUE =
            "previous-request-id";

    private final RequestIdFilter filter =
            new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void validClientRequestIdIsPreserved() throws Exception {
        MockHttpServletRequest request = requestWithId(
                "client-123"
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(request, response);

        assertRequestId(
                request,
                response,
                "client-123"
        );
    }

    @Test
    void validRequestIdIsTrimmed() throws Exception {
        MockHttpServletRequest request = requestWithId(
                "  client-123  "
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(request, response);

        assertRequestId(
                request,
                response,
                "client-123"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "client-123",
            "client_123",
            "client.123",
            "CLIENT123",
            "1234567890"
    })
    void allowedRequestIdCharactersAreAccepted(
            String requestId
    ) throws Exception {
        MockHttpServletRequest request =
                requestWithId(requestId);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(request, response);

        assertRequestId(
                request,
                response,
                requestId
        );
    }

    @Test
    void requestIdWithExactly128CharactersIsAccepted()
            throws Exception {
        String requestId = "a".repeat(128);

        MockHttpServletRequest request =
                requestWithId(requestId);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(request, response);

        assertRequestId(
                request,
                response,
                requestId
        );
    }

    @Test
    void missingRequestIdIsReplacedWithUuid()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest();
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(request, response);

        assertGeneratedRequestId(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "\t",
            "bad id",
            "bad!",
            "client/123",
            "client:123",
            "client@123"
    })
    void invalidRequestIdIsReplacedWithUuid(
            String requestId
    ) throws Exception {
        MockHttpServletRequest request =
                requestWithId(requestId);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(request, response);

        assertGeneratedRequestId(request, response);
    }

    @Test
    void requestIdLongerThan128CharactersIsReplacedWithUuid()
            throws Exception {
        MockHttpServletRequest request = requestWithId(
                "a".repeat(129)
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(request, response);

        assertGeneratedRequestId(request, response);
    }

    @Test
    void requestIdIsAvailableInMdcDuringFilterChain()
            throws Exception {
        MockHttpServletRequest request = requestWithId(
                "client-123"
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        AtomicBoolean chainInvoked =
                new AtomicBoolean(false);

        FilterChain chain = (servletRequest, servletResponse) -> {
            chainInvoked.set(true);

            assertThat(MDC.get(
                    RequestIdFilter.REQUEST_ID_ATTRIBUTE
            )).isEqualTo("client-123");

            assertThat(servletRequest.getAttribute(
                    RequestIdFilter.REQUEST_ID_ATTRIBUTE
            )).isEqualTo("client-123");
        };

        filter.doFilter(request, response, chain);

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void mdcValueIsRemovedAfterRequestWhenNoPreviousValueExists()
            throws Exception {
        MockHttpServletRequest request = requestWithId(
                "client-123"
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(request, response);

        assertThat(MDC.get(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        )).isNull();
    }

    @Test
    void previousMdcValueIsRestoredAfterRequest()
            throws Exception {
        MDC.put(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                PREVIOUS_MDC_VALUE
        );

        MockHttpServletRequest request = requestWithId(
                "client-123"
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(MDC.get(
                        RequestIdFilter.REQUEST_ID_ATTRIBUTE
                )).isEqualTo("client-123");

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        )).isEqualTo(PREVIOUS_MDC_VALUE);
    }

    @Test
    void previousMdcValueIsRestoredWhenFilterChainThrows() {
        MDC.put(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                PREVIOUS_MDC_VALUE
        );

        MockHttpServletRequest request = requestWithId(
                "client-123"
        );
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        FilterChain failingChain =
                (servletRequest, servletResponse) -> {
                    assertThat(MDC.get(
                            RequestIdFilter.REQUEST_ID_ATTRIBUTE
                    )).isEqualTo("client-123");

                    throw new ServletException("chain failure");
                };

        assertThatThrownBy(() ->
                filter.doFilter(
                        request,
                        response,
                        failingChain
                )
        )
                .isInstanceOf(ServletException.class)
                .hasMessage("chain failure");

        assertThat(MDC.get(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        )).isEqualTo(PREVIOUS_MDC_VALUE);
    }

    private void execute(
            MockHttpServletRequest request,
            MockHttpServletResponse response
    ) throws ServletException, IOException {
        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    // Цепочка успешно продолжена.
                }
        );
    }

    private MockHttpServletRequest requestWithId(
            String requestId
    ) {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                RequestIdFilter.REQUEST_ID_HEADER,
                requestId
        );

        return request;
    }

    private void assertRequestId(
            MockHttpServletRequest request,
            MockHttpServletResponse response,
            String expectedRequestId
    ) {
        assertThat(request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        )).isEqualTo(expectedRequestId);

        assertThat(response.getHeader(
                RequestIdFilter.REQUEST_ID_HEADER
        )).isEqualTo(expectedRequestId);
    }

    private void assertGeneratedRequestId(
            MockHttpServletRequest request,
            MockHttpServletResponse response
    ) {
        Object requestAttribute = request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );

        assertThat(requestAttribute)
                .isInstanceOf(String.class);

        String requestId = (String) requestAttribute;

        assertThat(requestId).isNotBlank();

        assertThat(response.getHeader(
                RequestIdFilter.REQUEST_ID_HEADER
        )).isEqualTo(requestId);

        assertThat(UUID.fromString(requestId))
                .isNotNull();
    }
}