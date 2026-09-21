package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Commits the logical execution plan before any provider I/O begins. */
@Service
public class AiExecutionPlanService {
    private final ModelExecutionPlanRepository plans;
    private final Clock clock;
    public AiExecutionPlanService(ModelExecutionPlanRepository plans, Clock clock) {
        this.plans = plans;
        this.clock = clock;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ModelExecutionPlanEntity createOrRead(AiExecutionRequest request) {
        // Replaying the logical execution is forbidden, regardless of its
        // prior physical outcome. Duplicate concurrent starts are protected
        // by the UNIQUE provider_operation_id index in V50.
        if (plans.findByProviderOperationId(
                request.aiRequest().providerOperationId()).isPresent()) {
            throw new IllegalStateException(
                    "Execution plan already exists; use ChatTurn replay/reconciliation, never repeat provider I/O");
        }
        ModelExecutionPlanEntity plan = ModelExecutionPlanEntity.create(
                request.aiRequest().providerOperationId(), request.chatTurnId(),
                request.aiRequest().organizationId(), request.modelRouteDecisionId(),
                request.target().requestedPhysicalModel(), clock.instant());
        plan.requireMatches(request);
        return plans.saveAndFlush(plan);
    }
}
