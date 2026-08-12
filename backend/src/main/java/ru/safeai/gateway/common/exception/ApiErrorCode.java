package ru.safeai.gateway.common.exception;

/**
 * Stable machine-readable API error codes.
 *
 * <p>Значения являются частью внешнего HTTP API contract.
 * Переименование или удаление существующего значения требует
 * отдельной API migration.</p>
 */
public enum ApiErrorCode {

    BAD_REQUEST,

    VALIDATION_ERROR,

    UNAUTHORIZED,

    /**
     * JWT был успешно распознан, но больше не является действительным
     * из-за изменения security state пользователя или организации:
     *
     * <ul>
     *     <li>tokenVersion изменился;</li>
     *     <li>organizationAuthVersion изменился;</li>
     *     <li>пользователь отключён;</li>
     *     <li>организация отключена;</li>
     *     <li>security status больше не существует.</li>
     * </ul>
     */
    TOKEN_REVOKED,

    FORBIDDEN,

    NOT_FOUND,

    CONFLICT,

    USER_VERSION_CONFLICT,

    ORGANIZATION_VERSION_CONFLICT,

    CHAT_BUSY,

    CHAT_LOCK_UNAVAILABLE,

    RATE_LIMIT_EXCEEDED,

    RATE_LIMIT_UNAVAILABLE,

    AUTH_SERVICE_UNAVAILABLE,

    EXPIRED_REFRESH_TOKEN,

    INVALID_REFRESH_TOKEN,

    AI_PROVIDER_TIMEOUT,

    AI_PROVIDER_RATE_LIMITED,

    AI_PROVIDER_OVERLOADED,

    AI_PROVIDER_UNAVAILABLE,

    AI_PROVIDER_ERROR,

    METHOD_NOT_ALLOWED,

    UNSUPPORTED_MEDIA_TYPE,

    NOT_ACCEPTABLE,

    INTERNAL_SERVER_ERROR
}