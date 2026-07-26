package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.exception.ApiErrorResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Component
@RequiredArgsConstructor
public class JsonSecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String error,
            String message
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        String requestId = (String) request.getAttribute(
                RequestIdFilter.REQUEST_ID_ATTRIBUTE
        );

        ApiErrorResponse body = new ApiErrorResponse(
                clock.instant(),
                status.value(),
                error,
                message,
                request.getRequestURI(),
                requestId,
                null
        );

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(response.getWriter(), body);
    }
}
