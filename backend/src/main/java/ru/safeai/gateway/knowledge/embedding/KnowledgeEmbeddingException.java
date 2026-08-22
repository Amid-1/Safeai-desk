package ru.safeai.gateway.knowledge.embedding;

import java.util.Objects;

public class KnowledgeEmbeddingException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public KnowledgeEmbeddingException(
            String code,
            String message,
            boolean retryable
    ) {
        this(
                code,
                message,
                retryable,
                null
        );
    }

    public KnowledgeEmbeddingException(
            String code,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(
                requireMessage(message),
                cause
        );

        this.code = requireCode(
                code
        );
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    private static String requireCode(
            String value
    ) {
        Objects.requireNonNull(
                value,
                "code не должен быть null"
        );

        String normalized =
                value.strip();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "code не должен быть пустым"
            );
        }

        return normalized;
    }

    private static String requireMessage(
            String value
    ) {
        Objects.requireNonNull(
                value,
                "message не должен быть null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "message не должен быть пустым"
            );
        }

        return value;
    }
}