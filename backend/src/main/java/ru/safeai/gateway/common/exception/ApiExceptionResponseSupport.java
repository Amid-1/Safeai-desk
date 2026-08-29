package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import ru.safeai.gateway.common.security.RequestIdFilter;

import java.util.List;
import java.util.Map;

final class ApiExceptionResponseSupport {

    static final String VALIDATION_MESSAGE =
            "Ошибка валидации запроса";
    static final String INTERNAL_ERROR_MESSAGE =
            "Внутренняя ошибка сервера";

    private final ApiErrorResponseFactory errorResponseFactory;

    ApiExceptionResponseSupport(
            ApiErrorResponseFactory errorResponseFactory
    ) {
        this.errorResponseFactory = errorResponseFactory;
    }

    ResponseEntity<ApiErrorResponse> validationResponse(
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors
    ) {
        Map<String, List<String>> normalized =
                fieldErrors == null || fieldErrors.isEmpty()
                        ? Map.of(
                        "request",
                        List.of("Некорректное значение")
                )
                        : fieldErrors;

        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR,
                VALIDATION_MESSAGE,
                request,
                normalized,
                null
        );
    }

    ResponseEntity<ApiErrorResponse> internalServerError(
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_MESSAGE,
                request,
                null,
                null
        );
    }

    ResponseEntity<ApiErrorResponse> build(
            HttpStatusCode status,
            ApiErrorCode error,
            String message,
            HttpServletRequest request,
            Map<String, List<String>> fieldErrors,
            HttpHeaders headers
    ) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(status);

        if (headers != null && !headers.isEmpty()) {
            builder.headers(headers);
        }

        builder.cacheControl(CacheControl.noStore());

        return builder.body(
                errorResponseFactory.create(
                        status,
                        error,
                        message,
                        request,
                        fieldErrors
                )
        );
    }

    ApiErrorCode codeForStatus(
            HttpStatusCode status
    ) {
        return switch (status.value()) {
            case 400 -> ApiErrorCode.BAD_REQUEST;
            case 401 -> ApiErrorCode.UNAUTHORIZED;
            case 403 -> ApiErrorCode.FORBIDDEN;
            case 404 -> ApiErrorCode.NOT_FOUND;
            case 405 -> ApiErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ApiErrorCode.NOT_ACCEPTABLE;
            case 409 -> ApiErrorCode.CONFLICT;
            case 415 -> ApiErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ApiErrorCode.RATE_LIMIT_EXCEEDED;
            default -> status.is5xxServerError()
                    ? ApiErrorCode.INTERNAL_SERVER_ERROR
                    : ApiErrorCode.BAD_REQUEST;
        };
    }

    String messageForStatus(
            HttpStatusCode status
    ) {
        return switch (status.value()) {
            case 400 -> "Некорректный запрос";
            case 401 -> "Требуется авторизация";
            case 403 -> "Доступ запрещён";
            case 404 -> "Ресурс не найден";
            case 405 -> "HTTP-метод не поддерживается для этого ресурса";
            case 406 -> "Запрошенный формат ответа не поддерживается";
            case 409 -> "Конфликт данных";
            case 415 -> "Тип содержимого запроса не поддерживается";
            case 429 -> "Превышен лимит запросов";
            default -> status.is5xxServerError()
                    ? INTERNAL_ERROR_MESSAGE
                    : "Ошибка запроса";
        };
    }

    String safeMessage(
            String message,
            String fallback
    ) {
        return message == null || message.isBlank()
                ? fallback
                : message.trim();
    }

    String requestId(
            HttpServletRequest request
    ) {
        Object value = request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );

        return value instanceof String requestId
                && !requestId.isBlank()
                ? requestId
                : "missing";
    }
}
