package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Записывает единый JSON-контракт ошибки из Spring Security filter chain.
 *
 * <p>Обычный {@link #write} не выставляет Bearer challenge. Это важно для
 * cookie-authentication: 401 из cookie/security-state pipeline не должен
 * притворяться OAuth2 Bearer challenge.</p>
 *
 * <p>{@link #writeBearerUnauthorized} используется только для Bearer/resource-server
 * 401 paths и добавляет {@code WWW-Authenticate: Bearer}.</p>
 */
@Component
@RequiredArgsConstructor
public class ApiErrorResponseWriter {

    private final JsonMapper jsonMapper;
    private final ApiErrorResponseFactory errorResponseFactory;

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatusCode status,
            ApiErrorCode error,
            String message
    ) throws IOException {
        writeInternal(
                request,
                response,
                status,
                error,
                message,
                false
        );
    }

    public void writeBearerUnauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            ApiErrorCode error,
            String message
    ) throws IOException {
        writeInternal(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                error,
                message,
                true
        );
    }

    private void writeInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatusCode status,
            ApiErrorCode error,
            String message,
            boolean bearerChallenge
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        /*
         * resetBuffer() очищает body, но сохраняет уже установленные headers,
         * в частности server X-Request-Id.
         */
        response.resetBuffer();
        response.setStatus(status.value());
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                "no-store"
        );

        if (bearerChallenge) {
            response.setHeader(
                    HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer"
            );
        }

        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );
        response.setContentType(
                MediaType.APPLICATION_JSON_VALUE
        );

        jsonMapper.writeValue(
                response.getOutputStream(),
                errorResponseFactory.create(
                        status,
                        error,
                        message,
                        request,
                        null
                )
        );
    }
}
