package ru.safeai.gateway.knowledge.ingestion;

public class KnowledgeIngestionException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public KnowledgeIngestionException(
            String code,
            String message,
            boolean retryable
    ) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public KnowledgeIngestionException(
            String code,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
