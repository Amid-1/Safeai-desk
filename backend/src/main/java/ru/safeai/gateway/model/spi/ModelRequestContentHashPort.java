package ru.safeai.gateway.model.spi;

import ru.safeai.gateway.knowledge.rag.KnowledgeMode;

import java.util.UUID;

/**
 * Semantic request hash supplied by Chat. Model uses it to verify immutable
 * routing evidence, without depending on Chat implementation or settings.
 *
 * The canonical hash format is intentionally unchanged.
 */
@FunctionalInterface
public interface ModelRequestContentHashPort {

    String requestHash(
            String normalizedContent,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    );
}
