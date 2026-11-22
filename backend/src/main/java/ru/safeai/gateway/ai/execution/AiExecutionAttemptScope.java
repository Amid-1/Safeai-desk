package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Carries durable physical-attempt recording across provider adapter internals.
 *
 * <p>A real provider call is legal only while a governed execution scope is
 * active for the exact provider operation. This makes the attempt ledger a
 * runtime invariant instead of a convention followed only by AiExecutionService.</p>
 */
@Component
public class AiExecutionAttemptScope {

    private static final int MAX_PHYSICAL_ATTEMPTS = 3;

    private final ThreadLocal<State> current = new ThreadLocal<>();

    public <T extends AiChatResponse> T execute(
            UUID operationId,
            AiExecutionAttemptObserver observer,
            Supplier<T> action
    ) {
        return execute(operationId, observer, MAX_PHYSICAL_ATTEMPTS, action);
    }

    public <T extends AiChatResponse> T execute(
            UUID operationId,
            AiExecutionAttemptObserver observer,
            int maxCalls,
            Supplier<T> action
    ) {
        if (maxCalls < 1 || maxCalls > MAX_PHYSICAL_ATTEMPTS) {
            throw new IllegalArgumentException("maxCalls must be between 1 and 3");
        }

        Objects.requireNonNull(operationId, "operationId не должен быть null");
        Objects.requireNonNull(observer, "observer не должен быть null");
        Objects.requireNonNull(action, "action не должен быть null");

        if (current.get() != null) {
            throw new IllegalStateException("Nested AI execution scope is forbidden");
        }

        State state = new State(operationId, observer, maxCalls);
        current.set(state);

        try {
            T response = action.get();

            if (state.lastAttemptNumber == 0) {
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

            // Only the exact response whose successful evidence was accepted
            // by the observer may leave the governed execution.
            if (state.successfulResponse == null || response != state.successfulResponse) {
                throw new IllegalStateException(
                        "AI provider adapter returned without matching successful physical evidence");
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

    /**
     * Fail-closed physical-I/O gate.
     *
     * <p>Provider adapters/retry executors must call this before creating an
     * attempt and before issuing network I/O.</p>
     */
    public void requireActive(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId не должен быть null");

        State state = required();

        if (!state.operationId.equals(operationId)) {
            throw new IllegalStateException(
                    "AI provider operationId does not match the active governed execution"
            );
        }
    }

    public void started(AiProviderAttemptContext attempt) {
        State state = required();
        Objects.requireNonNull(attempt, "attempt не должен быть null");

        requireActive(attempt.operationId());

        if (state.activeAttempt != null) {
            throw new IllegalStateException(
                    "Previous AI execution attempt is not terminal"
            );
        }

        if (attempt.attemptNumber() != state.lastAttemptNumber + 1) {
            throw new IllegalStateException(
                    "AI execution attempts must be reported sequentially"
            );
        }

        if (state.sealed) {
            throw new IllegalStateException("AI execution does not permit another physical attempt");
        }
        if (attempt.maxAttempts() > state.maxCalls
                || attempt.attemptNumber() > state.maxCalls) {
            throw new IllegalStateException("AI execution physical attempt cap exceeded");
        }
        if (state.declaredMaxAttempts != 0 && attempt.maxAttempts() != state.declaredMaxAttempts) {
            throw new IllegalStateException("AI execution attempt limit must remain unchanged");
        }
        if (!state.attemptIds.add(attempt.attemptId())) {
            throw new IllegalStateException("AI execution attempt ID must not be reused");
        }

        // A failed STARTED acknowledgement might represent a committed write.
        // Seal before the callback; only a confirmed return reopens execution.
        state.sealed = true;
        state.observer.started(attempt);
        state.activeAttempt = attempt;
        state.lastAttemptNumber = attempt.attemptNumber();
        state.declaredMaxAttempts = attempt.maxAttempts();
        state.dispatchAuthorized = false;
        state.sealed = false;
    }

    /**
     * Сохраняет порядок: долговечный STARTED -> проверка dispatch -> HTTP.
     * Не разрешает проверять чужую или уже завершённую попытку.
     */
    public void beforeNetworkDispatch(AiProviderAttemptContext attempt) {
        State state = active(attempt);
        if (state.sealed || state.dispatchAuthorized) {
            state.sealed = true;
            throw new IllegalStateException("AI physical dispatch authorization cannot be reused");
        }
        // An observer rejection must never allow a second authorization or retry.
        state.sealed = true;
        state.dispatchCheckInProgress = true;
        try {
            state.observer.beforeNetworkDispatch(attempt);
        } catch (RuntimeException denied) {
            // The callback did not return; the adapter cannot have entered I/O.
            // The recorder must additionally prove receipt absence under DB lock.
            state.activeAttempt = null;
            state.dispatchCheckInProgress = false;
            boolean confirmed;
            try {
                confirmed = state.observer.abortedBeforeDispatch(attempt, denied);
            } catch (RuntimeException recordingFailure) {
                if (recordingFailure != denied) denied.addSuppressed(recordingFailure);
                throw new PhysicalAttemptReconciliationRequiredException(attempt.attemptId(), denied);
            }
            if (!confirmed) {
                throw new PhysicalAttemptReconciliationRequiredException(attempt.attemptId(), denied);
            }
            throw new AiDispatchAbortedException(denied);
        } finally {
            state.dispatchCheckInProgress = false;
        }
        state.dispatchAuthorized = true;
        state.sealed = false;
    }

    public void succeeded(
            AiProviderAttemptContext attempt,
            AiChatResponse response
    ) {
        State state = active(attempt);
        Objects.requireNonNull(response, "response");
        if (!state.dispatchAuthorized || state.sealed) {
            state.sealed = true;
            throw new IllegalStateException("AI physical success requires dispatch authorization");
        }

        // A terminal write may have committed even if its acknowledgement is lost.
        // No subsequent terminal callback or STARTED may overwrite that evidence.
        state.sealed = true;
        state.activeAttempt = null;

        try {
            state.observer.succeeded(attempt, response);
            state.successfulResponse = response;
        } catch (AiProviderException governanceRejection) {
            // Physical success was already persisted by the observer.
            throw governanceRejection;
        } catch (RuntimeException terminalPersistenceFailure) {
            throw new PhysicalAttemptReconciliationRequiredException(
                    attempt.attemptId(),
                    terminalPersistenceFailure
            );
        }
    }

    public void failed(
            AiProviderAttemptContext attempt,
            AiProviderException exception
    ) {
        State state = active(attempt);
        Objects.requireNonNull(exception, "exception");
        boolean retryAllowed = !state.sealed && state.dispatchAuthorized
                && exception.isRetryable()
                && ProviderFailureCertainty.classify(exception) != OutcomeCertainty.AMBIGUOUS;
        state.sealed = true;
        state.activeAttempt = null;
        state.observer.failed(attempt, exception);
        state.sealed = !retryAllowed;
    }

    public void ambiguous(
            AiProviderAttemptContext attempt,
            RuntimeException exception
    ) {
        State state = active(attempt);
        Objects.requireNonNull(exception, "exception");
        state.sealed = true;
        state.activeAttempt = null;
        state.observer.ambiguous(attempt, exception);
    }

    public boolean active() {
        return current.get() != null;
    }

    public int permittedMaxAttempts(int requested) {
        if (requested < 1) {
            throw new IllegalArgumentException("requested must be positive");
        }

        State state = required();
        return Math.min(requested, state.maxCalls);
    }

    public boolean hasActiveAttempt(AiProviderAttemptContext attempt) {
        State state = current.get();

        return state != null
                && state.activeAttempt != null
                && state.activeAttempt.equals(attempt);
    }

    private State active(AiProviderAttemptContext attempt) {
        Objects.requireNonNull(attempt, "attempt не должен быть null");

        State state = required();

        if (state.dispatchCheckInProgress) {
            throw new IllegalStateException("AI execution observer cannot report nested attempt events");
        }
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
        AiProviderAttemptContext attempt = state.activeAttempt;

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
        AiProviderAttemptContext attempt = state.activeAttempt;

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
                    "No governed AI execution scope is active"
            );
        }

        return state;
    }

    private static final class State {
        private final UUID operationId;
        private final AiExecutionAttemptObserver observer;
        private final int maxCalls;
        private final Set<UUID> attemptIds = new HashSet<>();
        private boolean sealed;
        private boolean dispatchAuthorized;
        private boolean dispatchCheckInProgress;
        private int declaredMaxAttempts;
        private int lastAttemptNumber;
        private AiProviderAttemptContext activeAttempt;
        private AiChatResponse successfulResponse;

        private State(
                UUID operationId,
                AiExecutionAttemptObserver observer,
                int maxCalls
        ) {
            this.operationId = operationId;
            this.observer = observer;
            this.maxCalls = maxCalls;
        }
    }
}
