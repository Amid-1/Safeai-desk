package ru.safeai.gateway.knowledge.ingestion;

import ru.safeai.gateway.knowledge.chunking.KnowledgeChunkCandidate;

public record EmbeddedKnowledgeChunk(
        KnowledgeChunkCandidate chunk,
        float[] embedding
) {

    public EmbeddedKnowledgeChunk {
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
