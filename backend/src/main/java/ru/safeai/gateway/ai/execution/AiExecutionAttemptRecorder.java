package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable physical-attempt boundaries. The application checks predecessor
 * evidence for an actionable error; V53's serialized INSERT trigger is the
 * final authority for concurrent writers and direct SQL clients.
 */
@Service
public class AiExecutionAttemptRecorder {
    private final ModelExecutionAttemptRepository attempts;
    private final Clock clock;

    public AiExecutionAttemptRecorder(
            ModelExecutionAttemptRepository attempts,
            Clock clock
    ) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void started(
            UUID planId,
            ProviderExecutionTarget target,
            AiProviderAttemptContext attempt
    ) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attempt, "attempt");

        ModelExecutionAttemptEntity predecessor = attempts
                .findTopByExecutionPlanIdOrderByAttemptNumberDesc(planId)
                .orElse(null);
        if (predecessor == null) {
            if (attempt.attemptNumber() != 1) {
                throw new IllegalStateException("First physical attempt must be #1");
            }
        } else {
            if (attempt.attemptNumber() != predecessor.getAttemptNumber() + 1
                    || predecessor.getOutcome() != ExecutionAttemptOutcome.FAILED
                    || !isKnownTerminalFailure(predecessor)
                    || predecessor.getRetrySafety() != RetrySafety.SAME_TARGET_RETRY_ALLOWED) {
                throw new IllegalStateException(
                        "Previous physical attempt does not authorize another provider call");
            }
            requireSameTarget(predecessor, target);
        }
        attempts.saveAndFlush(ModelExecutionAttemptEntity.started(
                planId, attempt.attemptId(), attempt.attemptNumber(),
                target, clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(UUID attemptId, AiChatResponse response) {
        ModelExecutionAttemptEntity attempt = find(attemptId);
        attempt.succeeded(response, clock.instant());
        attempts.saveAndFlush(attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(UUID attemptId, AiProviderException exception) {
        ModelExecutionAttemptEntity attempt = find(attemptId);
        attempt.failed(exception, clock.instant());
        attempts.saveAndFlush(attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ambiguous(UUID attemptId, RuntimeException exception) {
        ModelExecutionAttemptEntity attempt = find(attemptId);
        attempt.ambiguous(exception.getClass().getSimpleName(), clock.instant());
        attempts.saveAndFlush(attempt);
    }

    private static boolean isKnownTerminalFailure(ModelExecutionAttemptEntity previous) {
        return previous.getOutcomeCertainty() == OutcomeCertainty.KNOWN_NOT_EXECUTED
                || previous.getOutcomeCertainty() == OutcomeCertainty.KNOWN_REJECTED;
    }

    /** Fallback to another deployment/configuration is not implemented. */
    private static void requireSameTarget(
            ModelExecutionAttemptEntity previous,
            ProviderExecutionTarget target
    ) {
        if (!previous.getProviderType().equals(target.providerType())
                || !previous.getProviderConfigurationRef().equals(target.providerConfigurationRef())
                || !previous.getProviderConfigurationVersion().equals(target.providerConfigurationVersion())
                || !previous.getDeploymentRef().equals(target.deploymentRef())
                || !previous.getDeploymentVersion().equals(target.deploymentVersion())
                || !previous.getRequestedPhysicalModel().equals(target.requestedPhysicalModel())) {
            throw new IllegalStateException(
                    "Same-target retry cannot change provider/configuration/deployment/model");
        }
    }

    private ModelExecutionAttemptEntity find(UUID attemptId) {
        return attempts.findByProviderAttemptId(attemptId).orElseThrow(
                () -> new IllegalStateException("Execution attempt is missing: " + attemptId));
    }
}
