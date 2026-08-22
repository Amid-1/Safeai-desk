package ru.safeai.gateway.knowledge.embedding;

public class KnowledgeEmbeddingException extends RuntimeException {

    public KnowledgeEmbeddingException(String message) {
        super(message);
    }

    public KnowledgeEmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
