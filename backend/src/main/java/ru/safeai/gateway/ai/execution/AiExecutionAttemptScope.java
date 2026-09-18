package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Carries a recorder across adapter internals without leaking Chat concerns
 * into provider implementations. Retry executors call the event methods for
 * every physical HTTP attempt. Adapters which do not implement that contract
 * are rejected by {@link AiExecutionService} before provider I/O.
 */
@Component
public class AiExecutionAttemptScope {
    private final ThreadLocal<State> current = new ThreadLocal<>();

    public <T extends AiChatResponse> T execute(
            UUID operationId,
            AiExecutionAttemptObserver observer,
            Supplier<T> action
    ) {
        Objects.requireNonNull(
                operationId,
                "operationId не должен быть null"
        );
        Objects.requireNonNull(
                observer,
                "observer не должен быть null"
        );
        Objects.requireNonNull(
                action,
                "action не должен быть null"
        );

        if (current.get() != null) {
            throw new IllegalStateException(
                    "Nested AI execution scope is forbidden"
            );
        }

        State state = new State(
                operationId,
                observer
        );
        current.set(state);

        try {
            T response = action.get();

            if (!state.eventSeen) {
                throw new IllegalStateException(
                        "AI provider adapter did not report a physical attempt"
                );
            }

            if (state.activeAttempt != null) {
                IllegalStateException incompleteAttempt =
                        new IllegalStateException(
                                "AI provider adapter returned before completing attempt "
                                        + state.activeAttempt.attemptId()
                        );
                recordAmbiguousOrSuppress(state, incompleteAttempt);
                throw incompleteAttempt;
            }

            return response;
        } catch (AiProviderException exception) {
            if (state.activeAttempt != null) {
                recordFailedOrSuppress(state, exception);
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (state.activeAttempt != null) {
                recordAmbiguousOrSuppress(state, exception);
            }
            throw exception;
        } finally {
            current.remove();
        }
    }

    public void started(AiProviderAttemptContext attempt) {
        State state = required();

        Objects.requireNonNull(
                attempt,
                "attempt не должен быть null"
        );

        if (state.activeAttempt != null) {
            throw new IllegalStateException(
                    "Previous AI execution attempt is not terminal"
            );
        }

        if (!state.operationId.equals(attempt.operationId())) {
            throw new IllegalStateException(
                    "AI execution attempt operationId does not match the active execution"
            );
        }

        if (attempt.attemptNumber()
                != state.lastAttemptNumber + 1) {
            throw new IllegalStateException(
                    "AI execution attempts must be reported sequentially"
            );
        }

        state.observer.started(attempt);
        state.eventSeen = true;
        state.activeAttempt = attempt;
        state.lastAttemptNumber = attempt.attemptNumber();
    }

    public void succeeded(
            AiProviderAttemptContext attempt,
            AiChatResponse response
    ) {
        State state = active(attempt);

        try {
            state.observer.succeeded(attempt, response);
        } finally {
            state.activeAttempt = null;
        }
    }

    public void failed(
            AiProviderAttemptContext attempt,
            AiProviderException exception
    ) {
        State state = active(attempt);
        state.observer.failed(attempt, exception);
        state.activeAttempt = null;
    }

    public void ambiguous(
            AiProviderAttemptContext attempt,
            RuntimeException exception
    ) {
        State state = active(attempt);
        state.observer.ambiguous(attempt, exception);
        state.activeAttempt = null;
    }

    public boolean active() {
        return current.get() != null;
    }

    public boolean hasActiveAttempt(
            AiProviderAttemptContext attempt
    ) {
        State state = current.get();

        return state != null
                && attempt != null
                && state.activeAttempt != null
                && state.activeAttempt.equals(attempt);
    }

    private State active(AiProviderAttemptContext attempt) {
        State state = required();

        if (state.activeAttempt == null
                || !state.activeAttempt.equals(attempt)) {
            throw new IllegalStateException(
                    "AI execution attempt event does not match the active attempt"
            );
        }

        return state;
    }

    private void recordFailedOrSuppress(
            State state,
            AiProviderException primary
    ) {
        AiProviderAttemptContext attempt =
                state.activeAttempt;

        try {
            failed(attempt, primary);
        } catch (RuntimeException recordingFailure) {
            primary.addSuppressed(recordingFailure);
        }
    }

    private void recordAmbiguousOrSuppress(
            State state,
            RuntimeException primary
    ) {
        AiProviderAttemptContext attempt =
                state.activeAttempt;

        try {
            ambiguous(attempt, primary);
        } catch (RuntimeException recordingFailure) {
            primary.addSuppressed(recordingFailure);
        }
    }

    private State required() {
        State state = current.get();

        if (state == null) {
            throw new IllegalStateException(
                    "No AI execution scope is active"
            );
        }

        return state;
    }

    private static final class State {
        private final UUID operationId;
        private final AiExecutionAttemptObserver observer;
        private boolean eventSeen;
        private int lastAttemptNumber;
        private AiProviderAttemptContext activeAttempt;

        private State(
                UUID operationId,
                AiExecutionAttemptObserver observer
        ) {
            this.operationId = operationId;
            this.observer = observer;
        }
    }
}
