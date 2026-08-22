package ru.safeai.gateway.knowledge.rag;

import ru.safeai.gateway.ai.dto.AiChatResponse;

import java.util.List;

public record RagCompletion(
        RagPreparation preparation,
        AiChatResponse response,
        List<RagCitation> citations,
        boolean citationsValid,
        boolean evidenceSufficient
) {
    public RagCompletion {
        citations = List.copyOf(citations);
    }

    public static RagCompletion general(
            RagPreparation preparation,
            AiChatResponse response
    ) {
        return new RagCompletion(
                preparation,
                response,
                List.of(),
                true,
                true
        );
    }
}
