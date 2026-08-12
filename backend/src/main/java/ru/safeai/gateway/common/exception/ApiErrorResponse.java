package ru.safeai.gateway.common.exception;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stable public API error contract.
 *
 * <p>fieldErrors никогда не равен null и всегда immutable.</p>
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        @Nullable String requestId,
        Map<String, List<String>> fieldErrors
) {

    public ApiErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path,
            @Nullable String requestId,
            Map<String, List<String>> fieldErrors
    ) {
        this.timestamp =
                Objects.requireNonNull(
                        timestamp,
                        "timestamp must not be null"
                );

        if (status < 100 || status > 599) {
            throw new IllegalArgumentException(
                    "status must be a valid HTTP status"
            );
        }

        this.status = status;

        this.error =
                requireText(
                        error,
                        "error"
                );

        this.message =
                requireText(
                        message,
                        "message"
                );

        this.path =
                path == null || path.isBlank()
                        ? "/"
                        : path.trim();

        this.requestId =
                requestId == null || requestId.isBlank()
                        ? null
                        : requestId.trim();

        this.fieldErrors =
                normalizeFieldErrors(
                        fieldErrors
                );
    }

    private static String requireText(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must not be blank"
            );
        }

        return value.trim();
    }

    private static Map<String, List<String>>
    normalizeFieldErrors(
            Map<String, List<String>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        LinkedHashMap<String, List<String>> normalized =
                new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry
                : source.entrySet()) {

            String field =
                    entry.getKey();

            if (field == null || field.isBlank()) {
                field = "_global";
            } else {
                field = field.trim();
            }

            List<String> messages =
                    normalizeMessages(
                            entry.getValue()
                    );

            if (!messages.isEmpty()) {
                /*
                 * Если несколько исходных ключей после trim/
                 * normalization превращаются в одно поле,
                 * сообщения объединяем, а не затираем.
                 */
                normalized.merge(
                        field,
                        messages,
                        ApiErrorResponse::mergeMessages
                );
            }
        }

        if (normalized.isEmpty()) {
            return Map.of();
        }

        return Collections.unmodifiableMap(
                normalized
        );
    }

    private static List<String> normalizeMessages(
            List<String> source
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        ArrayList<String> result =
                new ArrayList<>();

        for (String message : source) {
            if (message == null || message.isBlank()) {
                continue;
            }

            String normalized =
                    message.trim();

            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }

        return List.copyOf(result);
    }

    private static List<String> mergeMessages(
            List<String> first,
            List<String> second
    ) {
        if (first.isEmpty()) {
            return second;
        }

        if (second.isEmpty()) {
            return first;
        }

        ArrayList<String> merged =
                new ArrayList<>(
                        first.size()
                                + second.size()
                );

        merged.addAll(first);

        for (String message : second) {
            if (!merged.contains(message)) {
                merged.add(message);
            }
        }

        return List.copyOf(merged);
    }
}