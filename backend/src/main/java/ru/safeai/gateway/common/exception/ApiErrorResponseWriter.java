package ru.safeai.gateway.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.RequestIdFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final List<String> SAFE_HEADERS_TO_PRESERVE =
            List.of(
                    RequestIdFilter.REQUEST_ID_HEADER,
                    HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                    HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                    HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                    HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                    HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                    HttpHeaders.ACCESS_CONTROL_MAX_AGE,
                    HttpHeaders.VARY,
                    "Access-Control-Allow-Private-Network",
                    "Strict-Transport-Security",
                    "X-Content-Type-Options",
                    "X-Frame-Options",
                    "Referrer-Policy",
                    "Content-Security-Policy",
                    "Permissions-Policy"
            );

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
         * resetBuffer() preserved every previously written header, including
         * navigation/entity/cookie headers that are not safe to inherit into
         * an authentication/authorization error. Snapshot only explicitly
         * approved correlation/CORS/security headers, then perform a full
         * reset and rebuild the error response.
         */
        Map<String, List<String>> safeHeaders =
                snapshotSafeHeaders(
                        request,
                        response
                );

        response.reset();

        restoreSafeHeaders(
                response,
                safeHeaders
        );

        response.setStatus(
                status.value()
        );

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

    private Map<String, List<String>> snapshotSafeHeaders(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Map<String, List<String>> result =
                new LinkedHashMap<>();

        for (String headerName
                : SAFE_HEADERS_TO_PRESERVE) {

            List<String> values =
                    List.copyOf(
                            response.getHeaders(
                                    headerName
                            )
                    );

            if (!values.isEmpty()) {
                result.put(
                        headerName,
                        values
                );
            }
        }

        /*
         * RequestIdFilter owns this value server-side. Reconstruct it from the
         * request attribute if a downstream component removed the response
         * header before the security error was written.
         */
        if (!result.containsKey(
                RequestIdFilter.REQUEST_ID_HEADER
        )) {
            Object requestId =
                    request.getAttribute(
                            RequestIdFilter.REQUEST_ID_ATTRIBUTE
                    );

            if (requestId
                    instanceof String value
                    && !value.isBlank()) {

                result.put(
                        RequestIdFilter.REQUEST_ID_HEADER,
                        List.of(
                                value
                        )
                );
            }
        }

        return Map.copyOf(
                result
        );
    }

    private void restoreSafeHeaders(
            HttpServletResponse response,
            Map<String, List<String>> headers
    ) {
        headers.forEach(
                (headerName, values) -> {
                    if (values.isEmpty()) {
                        return;
                    }

                    response.setHeader(
                            headerName,
                            values.getFirst(
                            )
                    );

                    for (int index = 1;
                         index < values.size();
                         index++) {

                        response.addHeader(
                                headerName,
                                values.get(
                                        index
                                )
                        );
                    }
                }
        );
    }
}
