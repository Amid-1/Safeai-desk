package ru.safeai.gateway.common.exception;

import org.springframework.http.HttpStatusCode;

import java.util.Objects;

/**
 * Базовый тип контролируемых API-ошибок.
 *
 * <p>{@code publicMessage} разрешено возвращать клиенту.</p>
 *
 * <p>{@code internalMessage} предназначено только для логирования и
 * диагностики. Оно не должно возвращаться в HTTP response.</p>
 *
 * <p>В сообщения запрещено включать пароли, токены, cookie,
 * authorization headers и другие секреты.</p>
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final ApiErrorCode errorCode;
    private final String publicMessage;

    protected ApiException(
            HttpStatusCode status,
            ApiErrorCode errorCode,
            String publicMessage
    ) {
        this(
                status,
                errorCode,
                publicMessage,
                publicMessage,
                null
        );
    }

    protected ApiException(
            HttpStatusCode status,
            ApiErrorCode errorCode,
            String publicMessage,
            Throwable cause
    ) {
        this(
                status,
                errorCode,
                publicMessage,
                publicMessage,
                cause
        );
    }

    protected ApiException(
            HttpStatusCode status,
            ApiErrorCode errorCode,
            String publicMessage,
            String internalMessage,
            Throwable cause
    ) {
        super(
                requireMessage(
                        internalMessage,
                        "internalMessage"
                ),
                cause
        );

        this.status = Objects.requireNonNull(
                status,
                "status must not be null"
        );

        this.errorCode = Objects.requireNonNull(
                errorCode,
                "errorCode must not be null"
        );

        this.publicMessage = requireMessage(
                publicMessage,
                "publicMessage"
        );
    }

    public final HttpStatusCode getStatus() {
        return status;
    }

    public final ApiErrorCode getErrorCode() {
        return errorCode;
    }

    public final String getPublicMessage() {
        return publicMessage;
    }

    private static String requireMessage(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        return value.trim();
    }
}