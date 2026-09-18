package ru.safeai.gateway.ai.execution;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.provider.AiProvider;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.util.Objects;

/**
 * Generic execution boundary. Chat owns the user-visible lifecycle; this
 * service owns physical target verification, provider I/O and attempt
 * evidence.
 */
@Service
public class AiExecutionService {
    private final AiProvider provider;
    private final AiProviderProperties properties;
    private final AiExecutionPlanService planService;
    private final AiExecutionAttemptRecorder recorder;
    private final AiExecutionAttemptScope scope;

    public AiExecutionService(
            AiProvider provider,
            AiProviderProperties properties,
            AiExecutionPlanService planService,
            AiExecutionAttemptRecorder recorder,
            AiExecutionAttemptScope scope
    ) {
        this.provider = Objects.requireNonNull(
                provider,
                "provider не должен быть null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
        this.planService = Objects.requireNonNull(
                planService,
                "planService не должен быть null"
        );
        this.recorder = Objects.requireNonNull(
                recorder,
                "recorder не должен быть null"
        );
        this.scope = Objects.requireNonNull(
                scope,
                "scope не должен быть null"
        );
    }

    public AiExecutionResult execute(AiExecutionRequest request) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        if (!provider.recordsPhysicalAttempts()) {
            throw new IllegalStateException(
                    "AI provider adapter does not support durable attempt evidence"
            );
        }

        ProviderExecutionTarget activeTarget =
                requireActiveTarget();

        if (!activeTarget.equals(request.target())) {
            throw new IllegalStateException(
                    "Selected execution target differs from active provider configuration"
            );
        }

        ModelExecutionPlanEntity plan = planService.createOrRead(request);

        AiChatResponse response = scope.execute(
                request.aiRequest().providerOperationId(),
                new Observer(plan, activeTarget),
                () -> provider.sendMessage(request.aiRequest())
        );

        return new AiExecutionResult(plan.getId(), response);
    }

    public ProviderExecutionTarget targetFor(String requestedPhysicalModel) {
        ProviderExecutionTarget activeTarget =
                requireActiveTarget();

        String requested = Objects.requireNonNull(
                requestedPhysicalModel,
                "requestedPhysicalModel не должен быть null"
        ).trim();

        if (!activeTarget.requestedPhysicalModel().equals(requested)) {
            throw new IllegalStateException(
                    "Routed physical model differs from active provider configuration"
            );
        }

        return activeTarget;
    }

    private ProviderExecutionTarget requireActiveTarget() {
        ProviderExecutionTarget activeTarget =
                Objects.requireNonNull(
                        provider.executionTarget(),
                        "provider.executionTarget не должен быть null"
                );

        if (!properties.provider().equals(activeTarget.providerType())) {
            throw new IllegalStateException(
                    "Active provider target differs from safeai.ai.provider"
            );
        }

        return activeTarget;
    }

    private final class Observer implements AiExecutionAttemptObserver {
        private final ModelExecutionPlanEntity plan;
        private final ProviderExecutionTarget target;

        private Observer(
                ModelExecutionPlanEntity plan,
                ProviderExecutionTarget target
        ) {
            this.plan = plan;
            this.target = target;
        }

        @Override
        public void started(AiProviderAttemptContext attempt) {
            recorder.started(
                    plan.getId(),
                    target,
                    attempt
            );
        }

        @Override
        public void succeeded(
                AiProviderAttemptContext attempt,
                AiChatResponse response
        ) {
            /*
             * The physical provider call completed and may be billable even
             * when its resolved model violates the deployment contract.
             * Persist complete usage/pricing evidence first, then fail the
             * logical execution closed.
             */
            recorder.succeeded(
                    attempt.attemptId(),
                    response
            );

            target.requireApprovedResponse(
                    response.requestedModel(),
                    response.model(),
                    response.providerRequestId()
            );
        }

        @Override
        public void failed(
                AiProviderAttemptContext attempt,
                ru.safeai.gateway.ai.exception.AiProviderException exception
        ) {
            recorder.failed(
                    attempt.attemptId(),
                    exception
            );
        }

        @Override
        public void ambiguous(
                AiProviderAttemptContext attempt,
                RuntimeException exception
        ) {
            recorder.ambiguous(
                    attempt.attemptId(),
                    exception
            );
        }
    }
}
