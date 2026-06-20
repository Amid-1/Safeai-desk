package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class ApiErrorResponseFactory {

    public ApiErrorResponse create(
            HttpStatus status,
            String error,
            String message,
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors
    ) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);

        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                error,
                message,
                request.getRequestURI(),
                requestId,
                fieldErrors
        );
    }
}