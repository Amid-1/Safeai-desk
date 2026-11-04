package ru.safeai.gateway.knowledge.rag;

import java.io.Serial;

/**
 * Независимый доменный сигнал об отзыве RAG-контекста.
 *
 * <p>Не наследуется от IllegalStateException: JPA может переводить
 * такое исключение в InvalidDataAccessApiUsageException. При этом
 * RuntimeException по умолчанию вызывает откат Spring-транзакции.</p>
 */
public final class KnowledgeRagContextRevokedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public KnowledgeRagContextRevokedException() {
        super("Источник знаний больше не доступен для AI-запроса.");
    }
}
