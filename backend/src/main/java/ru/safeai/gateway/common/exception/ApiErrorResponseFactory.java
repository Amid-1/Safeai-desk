package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ApiErrorResponseFactory {

    private final Clock clock;

    public ApiErrorResponseFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public ApiErrorResponse create(
            HttpStatusCode status,
            ApiErrorCode error,
            String message,
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors
    ) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(request, "request must not be null");

        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            path = "/";
        }

        Object requestIdAttribute = request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );

        String requestId = requestIdAttribute instanceof String value
                && !value.isBlank()
                ? value
                : null;

        return new ApiErrorResponse(
                clock.instant(),
                status.value(),
                error.name(),
                message,
                path,
                requestId,
                fieldErrors
        );
    }
}
