package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Единый JSON writer для Spring Security filter chain.
 */
@Component
@RequiredArgsConstructor
public final class ApiErrorResponseWriter {

    private final JsonMapper jsonMapper;
    private final ApiErrorResponseFactory
            errorResponseFactory;

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatusCode status,
            ApiErrorCode error,
            String message
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        response.resetBuffer();
        response.setStatus(
                status.value()
        );
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                "no-store"
        );
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
