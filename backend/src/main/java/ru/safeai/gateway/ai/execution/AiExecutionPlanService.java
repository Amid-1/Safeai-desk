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
        return plans.findByProviderOperationId(request.aiRequest().providerOperationId())
                .orElseGet(() -> plans.saveAndFlush(ModelExecutionPlanEntity.create(
                        request.aiRequest().providerOperationId(), request.chatTurnId(),
                        request.aiRequest().organizationId(), request.modelRouteDecisionId(),
                        request.target().requestedPhysicalModel(), clock.instant()
                )));
    }
}
