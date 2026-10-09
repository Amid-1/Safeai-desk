package ru.safeai.gateway.ai.execution;

public enum ResponseValidationStatus {
    ACCEPTED,
    PROTOCOL_REJECTED,
    UNSUPPORTED_TOOL_USE,
    INVALID_SCHEMA
}
