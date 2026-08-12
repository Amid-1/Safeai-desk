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

/**
 * Server-owned request correlation.
 *
 * <p>Входящий X-Request-Id считается только clientRequestId.
 * Server requestId всегда генерируется backend и никогда не
 * наследует клиентское значение.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter
        extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE =
            "requestId";

    public static final String CLIENT_REQUEST_ID_ATTRIBUTE =
            "clientRequestId";

    public static final String REQUEST_ID_MDC_KEY =
            "requestId";

    public static final String CLIENT_REQUEST_ID_MDC_KEY =
            "clientRequestId";

    public static final String REQUEST_ID_HEADER =
            "X-Request-Id";

    private static final int
            MAX_CLIENT_REQUEST_ID_LENGTH = 128;

    private static final Pattern
            CLIENT_REQUEST_ID_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9._-]+$"
            );

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String serverRequestId =
                UUID.randomUUID().toString();

        String clientRequestId =
                normalizeClientRequestId(
                        request.getHeader(
                                REQUEST_ID_HEADER
                        )
                );

        String previousRequestId =
                MDC.get(
                        REQUEST_ID_MDC_KEY
                );

        String previousClientRequestId =
                MDC.get(
                        CLIENT_REQUEST_ID_MDC_KEY
                );

        request.setAttribute(
                REQUEST_ID_ATTRIBUTE,
                serverRequestId
        );

        if (clientRequestId == null) {
            request.removeAttribute(
                    CLIENT_REQUEST_ID_ATTRIBUTE
            );
        } else {
            request.setAttribute(
                    CLIENT_REQUEST_ID_ATTRIBUTE,
                    clientRequestId
            );
        }

        response.setHeader(
                REQUEST_ID_HEADER,
                serverRequestId
        );

        MDC.put(
                REQUEST_ID_MDC_KEY,
                serverRequestId
        );

        if (clientRequestId == null) {
            MDC.remove(
                    CLIENT_REQUEST_ID_MDC_KEY
            );
        } else {
            MDC.put(
                    CLIENT_REQUEST_ID_MDC_KEY,
                    clientRequestId
            );
        }

        try {
            filterChain.doFilter(
                    request,
                    response
            );
        } finally {
            restoreMdc(
                    REQUEST_ID_MDC_KEY,
                    previousRequestId
            );

            restoreMdc(
                    CLIENT_REQUEST_ID_MDC_KEY,
                    previousClientRequestId
            );
        }
    }

    private @Nullable String
    normalizeClientRequestId(
            @Nullable String requestId
    ) {
        if (requestId == null) {
            return null;
        }

        String normalized =
                requestId.trim();

        if (normalized.isBlank()
                || normalized.length()
                > MAX_CLIENT_REQUEST_ID_LENGTH
                || !CLIENT_REQUEST_ID_PATTERN
                .matcher(normalized)
                .matches()) {
            return null;
        }

        return normalized;
    }

    private void restoreMdc(
            String key,
            @Nullable String previousValue
    ) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(
                    key,
                    previousValue
            );
        }
    }
}
