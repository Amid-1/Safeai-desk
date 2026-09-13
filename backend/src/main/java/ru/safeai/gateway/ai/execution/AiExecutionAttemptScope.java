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
 * every physical HTTP attempt. Non-retrying adapters receive one synthetic
 * attempt at the same boundary.
 */
@Component
public class AiExecutionAttemptScope {
    private final ThreadLocal<State> current = new ThreadLocal<>();

    public <T extends AiChatResponse> T execute(
            UUID operationId,
            AiExecutionAttemptObserver observer,
            Supplier<T> action
    ) {
        Objects.requireNonNull(operationId, "operationId не должен быть null");
        Objects.requireNonNull(observer, "observer не должен быть null");
        State previous = current.get();
        if (previous != null) throw new IllegalStateException("Nested AI execution scope is forbidden");
        State state = new State(observer);
        current.set(state);
        AiProviderAttemptContext synthetic = new AiProviderAttemptContext(operationId, UUID.randomUUID(), 1, 1);
        try {
            T response = action.get();
            if (!state.started) {
                started(synthetic);
                succeeded(synthetic, response);
            }
            return response;
        } catch (AiProviderException exception) {
            if (!state.started) {
                started(synthetic);
                failed(synthetic, exception);
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (!state.started) {
                started(synthetic);
                ambiguous(synthetic, exception);
            }
            throw exception;
        } finally {
            current.remove();
        }
    }

    public void started(AiProviderAttemptContext attempt) {
        State state = required();
        state.started = true;
        state.observer.started(attempt);
    }
    public void succeeded(AiProviderAttemptContext attempt, AiChatResponse response) { required().observer.succeeded(attempt, response); }
    public void failed(AiProviderAttemptContext attempt, AiProviderException exception) { required().observer.failed(attempt, exception); }
    public void ambiguous(AiProviderAttemptContext attempt, RuntimeException exception) { required().observer.ambiguous(attempt, exception); }
    public boolean active() { return current.get() != null; }

    private State required() {
        State state = current.get();
        if (state == null) throw new IllegalStateException("No AI execution scope is active");
        return state;
    }
    private static final class State {
        private final AiExecutionAttemptObserver observer;
        private boolean started;
        private State(AiExecutionAttemptObserver observer) { this.observer = observer; }
    }
}
