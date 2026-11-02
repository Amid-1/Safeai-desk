// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/knowledge/rag/KnowledgeRagContextRevokedException.java
package ru.safeai.gateway.knowledge.rag;

/** Контекст Knowledge нельзя использовать после изменения состояния исходных данных. */
public final class KnowledgeRagContextRevokedException extends IllegalStateException {
    public KnowledgeRagContextRevokedException() {
        super("Источник знаний больше не доступен для AI-запроса.");
    }
}
