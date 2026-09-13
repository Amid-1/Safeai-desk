package ru.safeai.gateway.ai.execution;

/** What can be proven about a physical provider request. */
public enum OutcomeCertainty {
    KNOWN_NOT_EXECUTED,
    KNOWN_REJECTED,
    KNOWN_EXECUTED,
    AMBIGUOUS
}
