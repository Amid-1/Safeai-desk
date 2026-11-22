package ru.safeai.gateway.ai.execution;

/** Internal signal: the recorder committed proof of absence of a dispatch receipt. */
final class AiDispatchAbortedException extends RuntimeException {
    AiDispatchAbortedException(RuntimeException cause) {
        super("Physical dispatch denied before provider I/O", cause);
    }
}
