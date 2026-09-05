package ru.safeai.gateway.model.domain;

/** Sanitized result of an explicit administrative runtime connectivity probe. */
public enum RuntimeModelProbeStatus {
    AVAILABLE,
    AUTH_ERROR,
    RATE_LIMITED,
    MODEL_NOT_FOUND,
    UNAVAILABLE,
    CONFIGURATION_MISMATCH,
    ERROR
}
