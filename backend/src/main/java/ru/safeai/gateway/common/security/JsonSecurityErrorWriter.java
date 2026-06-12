package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

final class JsonSecurityErrorWriter {

    private JsonSecurityErrorWriter() {
    }

    static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String error,
            String message
    ) throws IOException {
        String json = """
                {
                  "timestamp": "%s",
                  "status": %d,
                  "error": "%s",
                  "message": "%s",
                  "path": "%s",
                  "fieldErrors": null
                }
                """.formatted(
                Instant.now(),
                status.value(),
                escapeJson(error),
                escapeJson(message),
                escapeJson(request.getRequestURI())
        );

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}