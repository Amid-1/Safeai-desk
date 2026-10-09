package ru.safeai.gateway.ai.execution;

public enum ExecutionAttemptOutcome {
    STARTED,
    SUCCEEDED,
    FAILED,
    AMBIGUOUS,
    PROTOCOL_REJECTED
}
