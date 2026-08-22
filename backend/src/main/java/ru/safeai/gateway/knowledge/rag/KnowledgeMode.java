package ru.safeai.gateway.knowledge.rag;

public enum KnowledgeMode {
    GENERAL,
    KNOWLEDGE_ASSISTED,
    KNOWLEDGE_ONLY;

    public boolean usesKnowledge() {
        return this != GENERAL;
    }
}
