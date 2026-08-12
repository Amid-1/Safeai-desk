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
 * <p>Класс владеет полным HTTP-контрактом security error response:
 * status, cache policy, Bearer challenge для 401, content type и JSON body.</p>
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
        if (response.isCommitted()) {
            return;
        }

        /*
         * resetBuffer() очищает body, но не уничтожает уже установленные
         * инфраструктурные headers, в частности server X-Request-Id.
         */
        response.resetBuffer();
        response.setStatus(status.value());
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                "no-store"
        );

        /*
         * Любой 401 этого writer относится к Bearer/resource-server
         * security pipeline. Challenge выставляется после resetBuffer(),
         * чтобы контракт не зависел от порядка вызовов entry point/filter.
         */
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
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
