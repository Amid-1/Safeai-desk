package ru.safeai.gateway.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void doFilterInternal_shouldGenerateRequestIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String requestId = response.getHeader(
                RequestIdFilter.REQUEST_ID_HEADER
        );

        assertThat(requestId).isNotBlank();
        assertThat(request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        )).isEqualTo(requestId);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_shouldUseValidRequestIdFromHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                RequestIdFilter.REQUEST_ID_HEADER,
                "request-123_test"
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .isEqualTo("request-123_test");

        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))
                .isEqualTo("request-123_test");

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_shouldGenerateNewRequestIdForInvalidHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                RequestIdFilter.REQUEST_ID_HEADER,
                "bad request id !!!"
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String requestId = response.getHeader(
                RequestIdFilter.REQUEST_ID_HEADER
        );

        assertThat(requestId).isNotBlank();
        assertThat(requestId).isNotEqualTo("bad request id !!!");

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_shouldGenerateNewRequestIdWhenHeaderIsTooLong() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                RequestIdFilter.REQUEST_ID_HEADER,
                "a".repeat(129)
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String requestId = response.getHeader(
                RequestIdFilter.REQUEST_ID_HEADER
        );

        assertThat(requestId).isNotBlank();
        assertThat(requestId).hasSizeLessThanOrEqualTo(128);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilterInternal_shouldClearMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                RequestIdFilter.REQUEST_ID_HEADER,
                "request-123"
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        )).isNull();

        verify(chain).doFilter(request, response);
    }
}
