package ru.safeai.gateway.knowledge.rag;

import java.util.UUID;

public record RagCitation(
        String label,
        int ordinal,
        UUID chunkId
) {
}
