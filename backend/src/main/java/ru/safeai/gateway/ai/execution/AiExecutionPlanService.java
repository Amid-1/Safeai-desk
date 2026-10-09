package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * Commits the logical execution plan before any provider I/O begins.
 *
 * <p>An already persisted, identity-equivalent plan is resumable only while it
 * still has zero physical attempts. This closes the crash window between plan
 * commit and the first durable STARTED attempt without permitting blind replay
 * after any physical-I/O evidence exists.</p>
 */
@Service
public class AiExecutionPlanService {

    private final ModelExecutionPlanRepository plans;
    private final ModelExecutionAttemptRepository attempts;
    private final Clock clock;

    public AiExecutionPlanService(
            ModelExecutionPlanRepository plans,
            ModelExecutionAttemptRepository attempts,
            Clock clock
    ) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ModelExecutionPlanEntity createOrRead(
            AiExecutionRequest request
    ) {
        Objects.requireNonNull(request, "request");

        ModelExecutionPlanEntity existing =
                plans.findByProviderOperationId(
                        request.aiRequest().providerOperationId()
                ).orElse(null);

        if (existing != null) {
            existing.requireMatches(request);

            boolean physicalAttemptExists =
                    attempts.findTopByExecutionPlanIdOrderByAttemptNumberDesc(
                            existing.getId()
                    ).isPresent();

            if (physicalAttemptExists) {
                throw new IllegalStateException(
                        "Execution plan already has physical attempt evidence; "
                                + "use ChatTurn replay/reconciliation and never "
                                + "blindly repeat provider I/O"
                );
            }

            /*
             * Safe recovery:
             * plan is durable, exact identity matches, and there is no STARTED
             * or terminal physical-attempt row. Therefore zero provider I/O is
             * durably proven for this plan.
             */
            return existing;
        }

        ModelExecutionPlanEntity plan =
                ModelExecutionPlanEntity.create(
                        request.aiRequest().providerOperationId(),
                        request.chatTurnId(),
                        request.aiRequest().organizationId(),
                        request.modelRouteDecisionId(),
                        request.target().requestedPhysicalModel(),
                        clock.instant()
                );

        plan.requireMatches(request);
        return plans.saveAndFlush(plan);
    }
}
