package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.service.ModelRouteExecutionBindingService;
import ru.safeai.gateway.model.service.ModelRouteExecutionIdentity;

import java.util.Objects;
import java.util.UUID;

/** Generic physical execution boundary with mandatory persisted route binding. */
@Service
public class AiExecutionService {
    private final AiProvider provider;
    private final AiProviderProperties properties;
    private final AiExecutionPlanService planService;
    private final AiExecutionAttemptRecorder recorder;
    private final AiExecutionAttemptScope scope;
    private final ModelRouteExecutionBindingService bindingService;

    public AiExecutionService(
            AiProvider provider,
            AiProviderProperties properties,
            AiExecutionPlanService planService,
            AiExecutionAttemptRecorder recorder,
            AiExecutionAttemptScope scope,
            ModelRouteExecutionBindingService bindingService
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.planService = Objects.requireNonNull(planService, "planService");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
    }

    public AiExecutionResult execute(AiExecutionRequest request) {
        Objects.requireNonNull(request, "request");

        // No plan / STARTED attempt / physical provider I/O before guard passes.
        bindingService.requireExecutable(
                request.executionIdentity(),
                request.chatTurnId(),
                request.modelRouteDecisionId(),
                request.reservedAiRequest(),
                request.aiRequest(),
                request.target().providerType(),
                request.target().requestedPhysicalModel()
        );

        if (!provider.recordsPhysicalAttempts()) {
            throw new IllegalStateException(
                    "AI provider adapter does not support durable attempt evidence");
        }
        ProviderExecutionTarget activeTarget = requireActiveTarget();
        if (!activeTarget.equals(request.target())) {
            throw new IllegalStateException(
                    "Selected execution target differs from active provider configuration");
        }

        // No plan can be created twice: retries belong only to this
        // particular execute call. HARD policies are single-physical-call
        // until a multi-attempt cost envelope is versioned and reserved.
        boolean oneAttemptOnly = bindingService.requiresSinglePhysicalAttempt(
                request.modelRouteDecisionId());
        ModelExecutionPlanEntity plan = planService.createOrRead(request);
        AiChatResponse response = scope.execute(
                request.aiRequest().providerOperationId(),
                new Observer(plan, activeTarget),
                oneAttemptOnly ? 1 : Integer.MAX_VALUE,
                () -> provider.sendMessage(request.aiRequest())
        );
        return new AiExecutionResult(plan.getId(), response);
    }

    /** Issue the DB-backed, immutable pre-RAG identity before provider I/O. */
    public ModelRouteExecutionIdentity bindExecution(
            UUID decisionId,
            UUID chatTurnId,
            UUID clientRequestId,
            UUID providerOperationId,
            AiChatRequest reservedAiRequest,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        return bindingService.bind(
                decisionId,
                chatTurnId,
                clientRequestId,
                providerOperationId,
                reservedAiRequest,
                knowledgeBaseId,
                knowledgeMode
        );
    }

    public ProviderExecutionTarget targetFor(String requestedPhysicalModel) {
        ProviderExecutionTarget activeTarget = requireActiveTarget();
        String requested = Objects.requireNonNull(
                requestedPhysicalModel, "requestedPhysicalModel").trim();
        if (!activeTarget.requestedPhysicalModel().equals(requested)) {
            throw new IllegalStateException(
                    "Routed physical model differs from active provider configuration");
        }
        return activeTarget;
    }

    private ProviderExecutionTarget requireActiveTarget() {
        ProviderExecutionTarget activeTarget = Objects.requireNonNull(
                provider.executionTarget(), "provider.executionTarget");
        if (!properties.provider().equals(activeTarget.providerType())) {
            throw new IllegalStateException(
                    "Active provider target differs from safeai.ai.provider");
        }
        return activeTarget;
    }

    private final class Observer implements AiExecutionAttemptObserver {
        private final ModelExecutionPlanEntity plan;
        private final ProviderExecutionTarget target;

        private Observer(ModelExecutionPlanEntity plan, ProviderExecutionTarget target) {
            this.plan = plan;
            this.target = target;
        }

        @Override
        public void started(AiProviderAttemptContext attempt) {
            recorder.started(plan.getId(), target, attempt);
        }

        @Override
        public void succeeded(AiProviderAttemptContext attempt, AiChatResponse response) {
            // The physical attempt may be billable even if the resolved target
            // violates our deployment contract: retain usage/pricing first.
            recorder.succeeded(attempt.attemptId(), response);
            target.requireApprovedResponse(
                    response.requestedModel(), response.model(), response.providerRequestId());
        }

        @Override
        public void failed(
                AiProviderAttemptContext attempt,
                ru.safeai.gateway.ai.exception.AiProviderException exception
        ) {
            recorder.failed(attempt.attemptId(), exception);
        }

        @Override
        public void ambiguous(AiProviderAttemptContext attempt, RuntimeException exception) {
            recorder.ambiguous(attempt.attemptId(), exception);
        }
    }
}
