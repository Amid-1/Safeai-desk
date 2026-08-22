package ru.safeai.gateway.knowledge.rag;

import ru.safeai.gateway.knowledge.retrieval.KnowledgeRetrievalHit;

public record KnowledgeContextSource(
        String label,
        KnowledgeRetrievalHit hit
) {
}
