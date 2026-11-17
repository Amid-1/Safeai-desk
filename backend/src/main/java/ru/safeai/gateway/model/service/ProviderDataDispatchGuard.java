package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.execution.AiDataAssurancePort;
import ru.safeai.gateway.ai.execution.AiExecutionRequest;
import ru.safeai.gateway.ai.execution.AiOrganizationExecutionDeniedException;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;

import java.time.Clock;

/**
 * Re-evaluates time-sensitive provider assurances
 * before every physical execution attempt.
 */
@Service
public class ProviderDataDispatchGuard implements AiDataAssurancePort {

    private final ModelRouteDecisionRepository decisions;
    private final OrganizationModelPolicyRepository policies;
    private final ModelCatalogRepository catalog;
    private final RuntimeModelStatusService runtime;
    private final Clock clock;

    public ProviderDataDispatchGuard(
            ModelRouteDecisionRepository decisions,
            OrganizationModelPolicyRepository policies,
            ModelCatalogRepository catalog,
            RuntimeModelStatusService runtime,
            Clock clock
    ) {
        this.decisions = decisions;
        this.policies = policies;
        this.catalog = catalog;
        this.runtime = runtime;
        this.clock = clock;
    }

    @Override
    public void require(
            AiExecutionRequest request,
            boolean started
    ) {
        var decision = decisions
                .findById(request.modelRouteDecisionId())
                .orElse(null);

        var organizationId = request.executionIdentity()
                .organizationId();

        if (decision == null
                || organizationId == null
                || !organizationId.equals(decision.organizationId())
                || decision.outcome() != ModelRouteOutcome.ALLOWED
                || !request.chatTurnId().equals(decision.chatTurnId())) {
            throw denial(request, started);
        }

        var original = policies
                .findById(organizationId, decision.policyId())
                .orElse(null);

        var latest = policies
                .findLatest(organizationId)
                .orElse(null);

        if (original == null || latest == null) {
            throw denial(request, started);
        }

        boolean requireNoTraining =
                requiresNoTraining(original)
                || requiresNoTraining(latest);

        boolean requireZeroDataRetention =
                requiresZeroDataRetention(original)
                || requiresZeroDataRetention(latest);

        if (!requireNoTraining && !requireZeroDataRetention) {
            return;
        }

        var entry = catalog
                .findById(decision.selectedCatalogEntryId())
                .orElse(null);

        var effective = catalog
                .findEffective(
                        decision.selectedModelKey(),
                        clock.instant()
                )
                .orElse(null);

        var current = runtime.current();

        if (!current.enabled()
                || isIncompatible(
                        entry,
                        current,
                        requireNoTraining,
                        requireZeroDataRetention
                )
                || isIncompatible(
                        effective,
                        current,
                        requireNoTraining,
                        requireZeroDataRetention
                )) {
            throw denial(request, started);
        }
    }

    private static boolean isIncompatible(
            ModelCatalogEntry entry,
            RuntimeModelStatusResponse current,
            boolean requireNoTraining,
            boolean requireZeroDataRetention
    ) {
        return entry == null
                || (entry.lifecycle() != ModelLifecycle.ACTIVE
                    && entry.lifecycle() != ModelLifecycle.DEPRECATED)
                || !entry.provider().equals(current.provider())
                || !entry.providerModelId().equals(current.model())
                || (requireNoTraining && (
                    !current.dataAssurance().noTraining()
                    || (entry.trainingUseStatus()
                            != ModelTrainingUseStatus.NOT_USED
                        && entry.trainingUseStatus()
                            != ModelTrainingUseStatus.CONTRACTUAL_NO_TRAINING)))
                || (requireZeroDataRetention && (
                    !current.dataAssurance().zeroDataRetention()
                    || entry.retentionStatus()
                            != ModelRetentionStatus.ZERO_DATA_RETENTION));
    }

    private static boolean requiresNoTraining(
            OrganizationModelPolicy policy
    ) {
        return policy.enabled()
                && policy.requireNoTraining();
    }

    private static boolean requiresZeroDataRetention(
            OrganizationModelPolicy policy
    ) {
        return policy.enabled()
                && policy.requireZeroDataRetention();
    }

    private static RuntimeException denial(
            AiExecutionRequest request,
            boolean started
    ) {
        String message =
                "Provider data protection assurance missing, expired or incompatible";

        if (!started) {
            return new AiOrganizationExecutionDeniedException(message);
        }

        return new AiProviderException(
                request.target().providerType(),
                request.target().requestedPhysicalModel(),
                null,
                null,
                "DATA_ASSURANCE_DENIED",
                AiProviderErrorType.PERMISSION_DENIED,
                false,
                false,
                null,
                message,
                null
        );
    }
}
