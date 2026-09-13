package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;


/** Generic execution boundary. Chat owns user lifecycle; this service owns I/O evidence. */
@Service
public class AiExecutionService {
    private final AiProvider provider;
    private final AiProviderProperties properties;
    private final AiExecutionPlanService planService;
    private final AiExecutionAttemptRecorder recorder;
    private final AiExecutionAttemptScope scope;
    public AiExecutionService(AiProvider provider, AiProviderProperties properties,
                              AiExecutionPlanService planService, AiExecutionAttemptRecorder recorder,
                              AiExecutionAttemptScope scope) {
        this.provider = provider; this.properties = properties; this.planService = planService;
        this.recorder = recorder; this.scope = scope;
    }
    public AiExecutionResult execute(AiExecutionRequest request) {
        if (!properties.provider().equals(request.target().providerType())) {
            throw new IllegalStateException("Selected execution target differs from active provider");
        }
        ModelExecutionPlanEntity plan = planService.createOrRead(request);
        AiChatResponse response = scope.execute(request.aiRequest().providerOperationId(), new Observer(plan, request.target()),
                () -> provider.sendMessage(request.aiRequest()));
        return new AiExecutionResult(plan.getId(), response);
    }
    public ProviderExecutionTarget targetFor(String requestedPhysicalModel) {
        return ProviderExecutionTarget.staticTarget(properties.provider(), requestedPhysicalModel);
    }
    private final class Observer implements AiExecutionAttemptObserver {
        private final ModelExecutionPlanEntity plan; private final ProviderExecutionTarget target;
        private Observer(ModelExecutionPlanEntity plan, ProviderExecutionTarget target) { this.plan = plan; this.target = target; }
        public void started(AiProviderAttemptContext attempt) { recorder.started(plan.getId(), target, attempt); }
        public void succeeded(AiProviderAttemptContext attempt, AiChatResponse response) {
            try {
                target.requireApprovedResolvedModel(response.model());
                recorder.succeeded(attempt.attemptId(), response);
            } catch (RuntimeException exception) {
                recorder.ambiguous(attempt.attemptId(), exception);
                throw exception;
            }
        }
        public void failed(AiProviderAttemptContext attempt, ru.safeai.gateway.ai.exception.AiProviderException exception) { recorder.failed(attempt.attemptId(), exception); }
        public void ambiguous(AiProviderAttemptContext attempt, RuntimeException exception) { recorder.ambiguous(attempt.attemptId(), exception); }
    }
}
