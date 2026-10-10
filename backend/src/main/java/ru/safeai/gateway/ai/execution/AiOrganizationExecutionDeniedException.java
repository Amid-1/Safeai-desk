package ru.safeai.gateway.ai.execution;

/**
 * Authorization refused before any new provider attempt was durably STARTED.
 * No network call has been admitted by this attempt; safe to fail the
 * ChatTurn without manufacturing AMBIGUOUS physical evidence.
 */
public final class AiOrganizationExecutionDeniedException extends RuntimeException {
    public AiOrganizationExecutionDeniedException(String message) {
        super(message);
    }

    public AiOrganizationExecutionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
