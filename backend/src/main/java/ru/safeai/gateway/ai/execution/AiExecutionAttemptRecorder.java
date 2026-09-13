package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.time.Clock;
import java.util.UUID;

/** Durable transaction boundaries on both sides of external I/O. */
@Service
public class AiExecutionAttemptRecorder {
    private final ModelExecutionAttemptRepository attempts;
    private final Clock clock;
    public AiExecutionAttemptRecorder(ModelExecutionAttemptRepository attempts, Clock clock) {
        this.attempts = attempts; this.clock = clock;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void started(UUID planId, ProviderExecutionTarget target, AiProviderAttemptContext attempt) {
        attempts.saveAndFlush(ModelExecutionAttemptEntity.started(planId, attempt.attemptId(), attempt.attemptNumber(), target, clock.instant()));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(UUID attemptId, AiChatResponse response) {
        ModelExecutionAttemptEntity attempt = find(attemptId); attempt.succeeded(response, clock.instant()); attempts.saveAndFlush(attempt);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(UUID attemptId, AiProviderException exception) {
        ModelExecutionAttemptEntity attempt = find(attemptId); attempt.failed(exception, clock.instant()); attempts.saveAndFlush(attempt);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ambiguous(UUID attemptId, RuntimeException exception) {
        ModelExecutionAttemptEntity attempt = find(attemptId); attempt.ambiguous(exception.getClass().getSimpleName(), clock.instant()); attempts.saveAndFlush(attempt);
    }
    private ModelExecutionAttemptEntity find(UUID attemptId) {
        return attempts.findByProviderAttemptId(attemptId).orElseThrow(() -> new IllegalStateException("Execution attempt is missing: " + attemptId));
    }
}
