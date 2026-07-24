package ru.safeai.gateway.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final Pattern REQUEST_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]+$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = normalizeRequestId(
                request.getHeader(REQUEST_ID_HEADER)
        );
        String previousMdcValue = MDC.get(REQUEST_ID_ATTRIBUTE);

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(REQUEST_ID_ATTRIBUTE, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousMdcValue == null) {
                MDC.remove(REQUEST_ID_ATTRIBUTE);
            } else {
                MDC.put(REQUEST_ID_ATTRIBUTE, previousMdcValue);
            }
        }
    }

    private String normalizeRequestId(@Nullable String requestId) {
        if (requestId == null) {
            return UUID.randomUUID().toString();
        }

        String normalized = requestId.trim();
        if (normalized.isBlank()
                || normalized.length() > MAX_REQUEST_ID_LENGTH
                || !REQUEST_ID_PATTERN.matcher(normalized).matches()) {
            return UUID.randomUUID().toString();
        }
        return normalized;
    }
}
