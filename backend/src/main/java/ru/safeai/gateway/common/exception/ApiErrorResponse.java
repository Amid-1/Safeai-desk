package ru.safeai.gateway.common.exception;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Единый JSON-контракт ошибки API.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String requestId,
        Map<String, List<String>> fieldErrors
) {
    public ApiErrorResponse {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        error = requireText(error, "error");
        message = requireText(message, "message");
        path = requireText(path, "path");

        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status code");
        }

        requestId = normalizeNullableText(requestId);
        fieldErrors = immutableFieldErrors(fieldErrors);
    }

    private static Map<String, List<String>> immutableFieldErrors(
            Map<String, List<String>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> copy = new LinkedHashMap<>();

        source.forEach((rawField, rawMessages) -> {
            String field = normalizeNullableText(rawField);

            if (field == null || rawMessages == null || rawMessages.isEmpty()) {
                return;
            }

            List<String> messages = rawMessages.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(message -> !message.isEmpty())
                    .distinct()
                    .toList();

            if (!messages.isEmpty()) {
                copy.put(field, List.copyOf(messages));
            }
        });

        return copy.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeNullableText(value);

        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return normalized;
    }

    private static String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
