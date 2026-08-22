package ru.safeai.gateway.knowledge.model;

public enum KnowledgeIngestionStatus {
    PENDING,
    VALIDATING,
    EXTRACTING,
    CHUNKING,
    READY,
    FAILED
}
