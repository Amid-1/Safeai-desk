package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Generic physical execution boundary with mandatory persisted route binding. */
@Service
public class AiExecutionService {
    private final AiProvider provider;
    private final AiProviderProperties properties;
    private final AiExecutionPlanService planService;
    private final AiExecutionAttemptRecorder recorder;
    private final AiExecutionAttemptScope scope;
    private final AiModelExecutionPort bindingService;
    private final AiExecutionAttemptAuthorization attemptAuthorization;

    /** Source-compatible constructor for unit tests that exercise GENERAL routes only. */
    public AiExecutionService(
            AiProvider provider,
            AiProviderProperties properties,
            AiExecutionPlanService planService,
            AiExecutionAttemptRecorder recorder,
            AiExecutionAttemptScope scope,
            AiModelExecutionPort bindingService
    ) {
        this(provider, properties, planService, recorder, scope, bindingService,
                AiExecutionAttemptAuthorization.noOp());
    }

    @Autowired
    public AiExecutionService(
            AiProvider provider,
            AiProviderProperties properties,
            AiExecutionPlanService planService,
            AiExecutionAttemptRecorder recorder,
            AiExecutionAttemptScope scope,
            AiModelExecutionPort bindingService,
            AiExecutionAttemptAuthorization attemptAuthorization
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.planService = Objects.requireNonNull(planService, "planService");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.attemptAuthorization = Objects.requireNonNull(attemptAuthorization, "attemptAuthorization");
    }

    public AiExecutionResult execute(AiExecutionRequest request) {
        Objects.requireNonNull(request, "request");

        // No plan / STARTED attempt / physical provider I/O before guard passes.
        bindingService.authorizePhysicalExecution(
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
        if (request.executionIdentity().usesKnowledge() && request.knowledgeExecutionPermitId() == null) {
            throw new IllegalStateException("Knowledge route is missing its durable execution permit");
        }
        ModelExecutionPlanEntity plan = planService.createOrRead(request);
        AiChatResponse response = scope.execute(
                request.aiRequest().providerOperationId(),
                new Observer(plan, activeTarget, request),
                oneAttemptOnly ? 1 : Integer.MAX_VALUE,
                () -> provider.sendMessage(request.aiRequest())
        );
        return new AiExecutionResult(plan.getId(), response);
    }

    /** Issue the DB-backed, immutable pre-RAG identity before provider I/O. */
    public AiExecutionIdentity bindExecution(
            UUID decisionId,
            UUID chatTurnId,
            UUID clientRequestId,
            UUID providerOperationId,
            AiChatRequest reservedAiRequest,
            UUID knowledgeBaseId,
            AiKnowledgeExecutionMode knowledgeMode
    ) {
        return bindingService.bindExecution(
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
        private final AiExecutionRequest request;
        private final ConcurrentMap<UUID, UUID> attemptLeases = new ConcurrentHashMap<>();

        private Observer(ModelExecutionPlanEntity plan, ProviderExecutionTarget target,
                         AiExecutionRequest request) {
            this.plan = plan;
            this.target = target;
            this.request = request;
        }

        @Override
        public void started(AiProviderAttemptContext attempt) {
            UUID leaseId = attemptAuthorization.beforePhysicalAttempt(request, attempt);
            try {
                recorder.started(plan.getId(), target, attempt);
                if (leaseId != null) attemptLeases.put(attempt.attemptId(), leaseId);
            } catch (RuntimeException failure) {
                if (leaseId != null) {
                    try {
                        attemptAuthorization.afterPhysicalAttempt(request, attempt, leaseId,
                                AiExecutionAttemptAuthorization.Outcome.ABORTED);
                    } catch (RuntimeException releaseFailure) {
                        failure.addSuppressed(releaseFailure);
                    }
                }
                throw failure;
            }
        }

        @Override
        public void beforeNetworkDispatch(AiProviderAttemptContext attempt) {
            UUID leaseId = attemptLeases.get(attempt.attemptId());
            if (request.executionIdentity().usesKnowledge() && leaseId == null) {
                throw new IllegalStateException(
                        "Knowledge dispatch is forbidden without an owned attempt lease");
            }
            attemptAuthorization.beforeNetworkDispatch(request, attempt, leaseId);
        }

        @Override
        public void succeeded(AiProviderAttemptContext attempt, AiChatResponse response) {
            // The physical attempt may be billable even if the resolved target
            // violates our deployment contract: retain usage/pricing first.
            recorder.succeeded(attempt.attemptId(), response);
            releaseLease(attempt, AiExecutionAttemptAuthorization.Outcome.SUCCEEDED);
            target.requireApprovedResponse(
                    response.requestedModel(), response.model(), response.providerRequestId());
        }

        @Override
        public void failed(
                AiProviderAttemptContext attempt,
                ru.safeai.gateway.ai.exception.AiProviderException exception
        ) {
            recorder.failed(attempt.attemptId(), exception);
            releaseLease(attempt, AiExecutionAttemptAuthorization.Outcome.FAILED);
        }

        @Override
        public void ambiguous(AiProviderAttemptContext attempt, RuntimeException exception) {
            recorder.ambiguous(attempt.attemptId(), exception);
            releaseLease(attempt, AiExecutionAttemptAuthorization.Outcome.AMBIGUOUS);
        }

        private void releaseLease(AiProviderAttemptContext attempt,
                                  AiExecutionAttemptAuthorization.Outcome outcome) {
            UUID leaseId = attemptLeases.get(attempt.attemptId());
            if (leaseId == null) {
                if (request.executionIdentity().usesKnowledge()) {
                    throw new IllegalStateException("Knowledge attempt lease is missing at terminal transition");
                }
                return;
            }
            attemptAuthorization.afterPhysicalAttempt(request, attempt, leaseId, outcome);
            attemptLeases.remove(attempt.attemptId(), leaseId);
        }
    }
}
