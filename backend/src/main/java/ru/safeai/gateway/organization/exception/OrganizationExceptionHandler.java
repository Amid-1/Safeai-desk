package ru.safeai.gateway.organization.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.ApiExceptionResponseSupport;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public final class OrganizationExceptionHandler {

    private final ApiExceptionResponseSupport responses;

    public OrganizationExceptionHandler(
            ApiErrorResponseFactory errorResponseFactory
    ) {
        this.responses = new ApiExceptionResponseSupport(
                errorResponseFactory
        );
    }

    @ExceptionHandler(OrganizationVersionConflictException.class)
    public org.springframework.http.ResponseEntity<ApiErrorResponse>
    handleOrganizationVersionConflict(
            OrganizationVersionConflictException exception,
            HttpServletRequest request
    ) {
        log.info(
                "Organization optimistic version conflict: requestId={}, "
                        + "organizationId={}, expectedVersion={}, actualVersion={}, path={}",
                responses.requestId(request),
                exception.getOrganizationId(),
                exception.getExpectedVersion(),
                exception.getActualVersion(),
                request.getRequestURI()
        );

        return responses.build(
                exception.getStatus(),
                exception.getErrorCode(),
                exception.getPublicMessage(),
                request,
                null,
                null
        );
    }
}
