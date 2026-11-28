package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.time.Clock;
import java.time.Instant;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Durable physical-attempt boundaries. The application checks predecessor
 * evidence for an actionable error; V53's serialized INSERT trigger is the
 * final authority for concurrent writers and direct SQL clients.
 */
@Service
public class AiExecutionAttemptRecorder {
    private final ModelExecutionAttemptRepository attempts;
    private final Clock clock;
    private final TransactionTemplate settlement;

    public AiExecutionAttemptRecorder(
            ModelExecutionAttemptRepository attempts,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.clock = Objects.requireNonNull(clock, "clock");
        settlement = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        settlement.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        settlement.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        settlement.setTimeout(10);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 15)
    public void started(
            UUID planId,
            ProviderExecutionTarget target,
            AiProviderAttemptContext attempt
    ) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attempt, "attempt");

        if (!attempts.organizationEpochCurrent(planId)) {
            throw new AiOrganizationExecutionDeniedException(
                    "Organization disabled or security epoch changed before physical STARTED");
        }

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
        try {
            attempts.saveAndFlush(ModelExecutionAttemptEntity.started(
                    planId, attempt.attemptId(), attempt.attemptNumber(),
                    target, clock.instant()));
        } catch (RuntimeException failure) {
            // Disable may win the row lock between the advisory preflight
            // and the authoritative V72 BEFORE INSERT trigger. Such a
            // rejection never commits STARTED and must not be AMBIGUOUS.
            if (isOrganizationEpochViolation(failure)) {
                throw new AiOrganizationExecutionDeniedException(
                        "Organization authorization revoked before STARTED", failure);
            }
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 15)
    public void beforeNetworkDispatch(UUID planId, UUID attemptId) {
        // REQUIRES_NEW commits the one-shot receipt before the adapter can
        // send HTTP. Any rejection/lost commit acknowledgement must stop I/O.
        try {
            if (!attempts.consumeDispatchAuthorization(Objects.requireNonNull(planId, "planId"),
                    Objects.requireNonNull(attemptId, "attemptId"))) {
                throw new IllegalStateException("Financial admission is no longer valid");
            }
        } catch (RuntimeException failure) {
            if (isOrganizationEpochViolation(failure)) {
                throw new AiOrganizationExecutionDeniedException("Organization authorization revoked before dispatch", failure);
            }
            throw failure;
        }
    }

    public void succeeded(UUID attemptId, AiChatResponse response) {
        Objects.requireNonNull(response, "response");
        settle(attemptId, (attempt, time) -> attempt.succeeded(response, time));
    }

    public void failed(UUID attemptId, AiProviderException exception) {
        Objects.requireNonNull(exception, "exception");
        settle(attemptId, (attempt, time) -> attempt.failed(exception, time));
    }

    public void ambiguous(UUID attemptId, RuntimeException exception) {
        Objects.requireNonNull(exception, "exception");
        settle(attemptId, (attempt, time) -> attempt.ambiguous(exception.getClass().getSimpleName(), time));
    }

    public void abortedBeforeDispatch(UUID attemptId) {
        settle(attemptId, ModelExecutionAttemptEntity::abortedBeforeDispatch);
    }

    private void settle(UUID attemptId, BiConsumer<ModelExecutionAttemptEntity, Instant> transition) {
        Objects.requireNonNull(attemptId, "attemptId");
        Instant observedAt = clock.instant();
        for (int n = 1; ; n++) {
            try {
                settlement.executeWithoutResult(status -> {
                    if (!attempts.lockSettlement(attemptId)) throw new IllegalStateException("Settlement lock unavailable");
                    ModelExecutionAttemptEntity attempt = attempts.findForSettlement(attemptId).orElseThrow(
                            () -> new IllegalStateException("Execution attempt is missing: " + attemptId));
                    if (attempt.getOutcome() == ExecutionAttemptOutcome.STARTED) {
                        transition.accept(attempt, observedAt);
                        attempts.saveAndFlush(attempt);
                    } else {
                        ModelExecutionAttemptEntity candidate = attempt.settlementCandidate();
                        transition.accept(candidate, observedAt);
                        if (!attempt.sameTerminalEvidence(candidate)) {
                            throw new IllegalStateException("Conflicting terminal evidence: " + attemptId);
                        }
                    }
                });
                return; // TransactionTemplate has returned AFTER commit.
            } catch (RuntimeException failure) {
                if (n >= 3 || !retryableSettlement(failure)) throw failure;
                // Only database settlement is retried; never call the provider here.
                try {
                    Thread.sleep(50L * n);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
    }

    static boolean retryableSettlement(Throwable failure) {
        boolean transientFailure = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                return state.startsWith("08") || state.startsWith("40")
                        || state.equals("55P03") || state.equals("57014");
            }
            transientFailure |= current instanceof TransientDataAccessException
                    || current instanceof RecoverableDataAccessException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof CannotCreateTransactionException
                    || current instanceof TransactionSystemException;
        }
        return transientFailure;
    }

    private static boolean isOrganizationEpochViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "v100_organization_epoch".equals(violation.getConstraintName())) return true;
            // V72 is immutable history and did not expose a named constraint.
            if (message != null && message.contains(
                    "V72 physical AI attempt denied: organization disabled or authorization epoch revoked")) {
                return true;
            }
        }
        return false;
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

}
