package ru.safeai.gateway.knowledge.exception;

import org.springframework.http.HttpStatus;
import ru.safeai.gateway.common.exception.ApiErrorCode;
import ru.safeai.gateway.common.exception.ApiException;

/** Controlled retryable-facing classification for object-storage outages. */
public final class KnowledgeStorageUnavailableException
        extends ApiException {

    private static final String PUBLIC_MESSAGE =
            "Хранилище документов временно недоступно.";

    public KnowledgeStorageUnavailableException(
            String internalMessage,
            Throwable cause
    ) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.INTERNAL_SERVER_ERROR,
                PUBLIC_MESSAGE,
                internalMessage,
                cause
        );
    }
}
