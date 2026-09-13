package ru.safeai.gateway.knowledge.model;

/** Stable wire states for knowledge-base readiness. */
public enum KnowledgeHealthState {
    DISABLED,
    EMPTY,
    HEALTHY,
    INDEXING,
    DEGRADED
}
