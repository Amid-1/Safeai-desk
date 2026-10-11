package ru.safeai.gateway.knowledge.rag;

import ru.safeai.gateway.ai.execution.AiKnowledgeExecutionMode;

public enum KnowledgeMode implements AiKnowledgeExecutionMode {
    GENERAL,
    KNOWLEDGE_ASSISTED,
    KNOWLEDGE_ONLY;

    public boolean usesKnowledge() {
        return this != GENERAL;
    }
}
