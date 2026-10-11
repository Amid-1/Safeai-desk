package ru.safeai.gateway.ai.execution;

/** Marker for a knowledge execution mode, verified by the model implementation. */
public interface AiKnowledgeExecutionMode {

    boolean usesKnowledge();
}
