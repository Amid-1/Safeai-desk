package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewItemResponse;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Backend-driven route/policy simulator; never persists a policy or calls a provider. */
@Service
public class ModelPolicyPreviewService {

    private static final UUID PREVIEW_UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PREVIEW_SHA256 = "0".repeat(64);

    private final ModelCatalogRepository catalogRepository;
    private final OrganizationModelPolicyRepository policyRepository;
    private final RuntimeModelStatusService runtimeStatusService;
    private final ModelRoutingSelectionPolicy selectionPolicy;
    private final ModelRoutingCostPolicy costPolicy;
    private final ModelRoutingEnvelopeService envelopeService;
    private final Clock clock;

    public ModelPolicyPreviewService(
            ModelCatalogRepository catalogRepository,
            OrganizationModelPolicyRepository policyRepository,
            ModelRouteDecisionRepository decisionRepository,
            RuntimeModelStatusService runtimeStatusService,
            ModelRoutingEnvelopeService envelopeService,
            Clock clock
    ) {
        this.catalogRepository = Objects.requireNonNull(
                catalogRepository,
                "catalogRepository не должен быть null"
        );
        this.policyRepository = Objects.requireNonNull(
                policyRepository,
                "policyRepository не должен быть null"
        );
        this.runtimeStatusService = Objects.requireNonNull(
                runtimeStatusService,
                "runtimeStatusService не должен быть null"
        );
        this.clock = Objects.requireNonNull(clock, "clock не должен быть null");
        this.envelopeService = Objects.requireNonNull(
                envelopeService, "envelopeService не должен быть null"
        );
        this.selectionPolicy = new ModelRoutingSelectionPolicy(catalogRepository);
        this.costPolicy = new ModelRoutingCostPolicy(
                Objects.requireNonNull(
                        decisionRepository,
                        "decisionRepository не должен быть null"
                )
        );
    }

    @Transactional(readOnly = true)
    public ModelPolicyPreviewResponse preview(
            UUID organizationId,
            CreateOrganizationModelPolicyVersionRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        UUID target = resolveVisibleOrganization(organizationId, currentUser);

        if (!policyRepository.organizationExists(target)) {
            throw new ResourceNotFoundException(
                    "Организация не найдена: " + target
            );
        }

        int currentVersion = policyRepository.findLatest(target)
                .map(OrganizationModelPolicy::version)
                .orElse(0);

        if (currentVersion != request.expectedPreviousVersion()) {
            throw new ConflictException(
                    "Model policy preview version conflict: expected previous version "
                            + request.expectedPreviousVersion()
                            + ", actual "
                            + currentVersion
            );
        }

        Instant now = DatabaseTimestampNormalizer.normalize(clock.instant());
        OrganizationModelPolicy draft = OrganizationModelPolicyRules.buildPolicy(
                PREVIEW_UUID,
                target,
                ModelControlPlaneNumericValidation.nextVersion(currentVersion),
                request,
                currentUser.getId(),
                now
        );
        RuntimeModelStatusResponse runtime = Objects.requireNonNull(
                runtimeStatusService.current(),
                "Runtime model status не должен быть null"
        );

        List<ModelCatalogEntry> effectiveCatalog = loadEffectiveCatalog(now);

        ModelRoutingCostPolicy.BudgetContext budgetContext =
                costPolicy.loadBudgetContextForPreview(
                        target,
                        draft,
                        draft.enabled(),
                        now
                );

        long minimumInputUnits = costPolicy.estimateInputTokens(
                syntheticRequest(
                        target,
                        currentUser.getId()
                )
        );

        List<ModelPolicyPreviewItemResponse> items = new ArrayList<>();
        int executable = 0;

        for (ModelCatalogEntry entry : effectiveCatalog) {
            ModelPolicyPreviewItemResponse item = evaluateEntry(
                    draft,
                    runtime,
                    entry,
                    budgetContext,
                    minimumInputUnits
            );
            items.add(item);
            if (item.outcome() == ModelRouteOutcome.ALLOWED) {
                executable++;
            }
        }

        AutomaticPreview automatic = evaluateAutomaticSelection(
                target,
                currentUser.getId(),
                draft,
                runtime,
                now,
                items
        );

        return new ModelPolicyPreviewResponse(
                target,
                currentVersion,
                draft.enabled(),
                now,
                runtime.provider(),
                runtime.model(),
                automatic.outcome(),
                automatic.reason(),
                automatic.modelKey(),
                executable,
                draft.enabled() && items.stream().noneMatch(item ->
                        item.outcome() == ModelRouteOutcome.ALLOWED
                                || item.reason() == ModelRouteReason.MONTHLY_BUDGET_EXCEEDED
                                || item.reason() == ModelRouteReason.MONTHLY_BUDGET_UNVERIFIABLE),
                items
        );
    }

    private List<ModelCatalogEntry> loadEffectiveCatalog(Instant now) {
        final int pageSize = 200;
        final int hardLimit = 5_000;
        List<ModelCatalogEntry> result = new ArrayList<>();
        String cursor = null;
        while (true) {
            List<ModelCatalogEntry> page = catalogRepository.findEffectivePage(
                    now,
                    cursor,
                    pageSize
            );
            result.addAll(page);
            if (result.size() > hardLimit) {
                throw new ConflictException(
                        "Model policy preview превышает безопасный лимит 5000 effective models"
                );
            }
            if (page.size() < pageSize) {
                return List.copyOf(result);
            }
            cursor = page.getLast().modelKey();
        }
    }

    private ModelPolicyPreviewItemResponse evaluateEntry(
            OrganizationModelPolicy policy,
            RuntimeModelStatusResponse runtime,
            ModelCatalogEntry entry,
            ModelRoutingCostPolicy.BudgetContext budgetContext,
            long minimumInputUnits
    ) {
        boolean policyEnabled = policy.enabled();
        ModelRouteReason reason = selectionPolicy.validateCatalogAndPolicy(
                entry,
                entry.modelKey(),
                runtime,
                policy,
                policyEnabled,
                Set.of()
        );

        long inputLimit = costPolicy.effectiveInputLimit(
                entry,
                runtime,
                policy,
                policyEnabled
        );
        long outputLimit = costPolicy.effectiveOutputLimit(
                entry,
                runtime,
                policy,
                policyEnabled
        );
        ModelRoutingCostPolicy.PricingEstimate maxEnvelopePricing =
                costPolicy.estimateCost(
                        entry,
                        runtime,
                        inputLimit,
                        outputLimit
                );
        ModelRoutingCostPolicy.PricingEstimate minimumRequestPricing =
                costPolicy.estimateCost(
                        entry,
                        runtime,
                        Math.min(minimumInputUnits, inputLimit),
                        outputLimit
                );

        ModelRoutingCostPolicy.BudgetSnapshot budget =
                ModelRoutingCostPolicy.BudgetSnapshot.none();

        if (reason == null && minimumInputUnits > inputLimit) {
            reason = ModelRouteReason.INPUT_LIMIT_EXCEEDED;
        }

        if (reason == null && policyEnabled) {
            if (policy.requireCompletePricing()
                    && !minimumRequestPricing.complete()) {
                reason = ModelRouteReason.PRICING_INCOMPLETE;
            } else if (policy.maxRequestCostUsd() != null) {
                if (!minimumRequestPricing.complete()
                        || minimumRequestPricing.cost() == null) {
                    reason = ModelRouteReason.PRICING_INCOMPLETE;
                } else if (minimumRequestPricing.cost()
                        .compareTo(policy.maxRequestCostUsd()) > 0) {
                    reason = ModelRouteReason.REQUEST_COST_LIMIT_EXCEEDED;
                }
            }
        }

        if (reason == null) {
            budget = costPolicy.evaluateBudget(
                    budgetContext,
                    policy,
                    minimumRequestPricing
            );
            if (budget.denialReason() != null) {
                reason = budget.denialReason();
            }
        }

        return new ModelPolicyPreviewItemResponse(
                entry.modelKey(),
                entry.version(),
                entry.provider(),
                entry.providerModelId(),
                entry.lifecycle(),
                reason == null
                        ? ModelRouteOutcome.ALLOWED
                        : ModelRouteOutcome.DENIED,
                reason,
                inputLimit,
                outputLimit,
                maxEnvelopePricing.complete(),
                ModelPolicyPreviewItemResponse.decimal(maxEnvelopePricing.cost()),
                ModelPolicyPreviewItemResponse.decimal(budget.monthlyBudgetUsd()),
                ModelPolicyPreviewItemResponse.decimal(budget.monthlyActualCostUsd()),
                ModelPolicyPreviewItemResponse.decimal(budget.monthlyReservedCostUsd()),
                ModelPolicyPreviewItemResponse.decimal(budget.monthlyCommittedUsd()),
                budget.monthlyUnknownCostCount(),
                ModelPolicyPreviewItemResponse.decimal(budget.monthlyProjectedUsd()),
                budget.monthlyCostKnown(),
                budget.monthlyBudgetUsd() == null
                        ? ru.safeai.gateway.model.domain.MonthlyCostState.NOT_EVALUATED
                        : budget.monthlyCostKnown()
                        ? ru.safeai.gateway.model.domain.MonthlyCostState.KNOWN
                        : ru.safeai.gateway.model.domain.MonthlyCostState.UNKNOWN,
                budget.monthlyCommitmentState(),
                budget.exceeded()
        );
    }

    private AutomaticPreview evaluateAutomaticSelection(
            UUID organizationId,
            UUID userId,
            OrganizationModelPolicy policy,
            RuntimeModelStatusResponse runtime,
            Instant now,
            List<ModelPolicyPreviewItemResponse> evaluatedItems
    ) {
        ModelRouteRequest synthetic = syntheticRequest(
                organizationId,
                userId
        );

        ModelRoutingSelectionPolicy.Selection selection = selectionPolicy.selectCatalog(
                synthetic,
                runtime,
                policy,
                policy.enabled(),
                now
        );

        if (selection.denialReason() != null) {
            return new AutomaticPreview(
                    ModelRouteOutcome.DENIED,
                    selection.denialReason(),
                    selection.modelKey()
            );
        }

        ModelCatalogEntry selectedEntry = Objects.requireNonNull(
                selection.entry(),
                "Resolved preview selection должна иметь catalog entry"
        );

        ModelPolicyPreviewItemResponse evaluated = evaluatedItems.stream()
                .filter(item -> item.modelKey().equals(selectedEntry.modelKey())
                        && item.catalogVersion() == selectedEntry.version())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Preview selection отсутствует в evaluated catalog: "
                                + selectedEntry.modelKey()
                                + " v"
                                + selectedEntry.version()
                ));

        if (evaluated.outcome() == ModelRouteOutcome.DENIED) {
            return new AutomaticPreview(
                    ModelRouteOutcome.DENIED,
                    Objects.requireNonNull(
                            evaluated.reason(),
                            "DENIED preview item должна иметь reason"
                    ),
                    selection.modelKey()
            );
        }

        return new AutomaticPreview(
                ModelRouteOutcome.ALLOWED,
                Objects.requireNonNull(
                        selection.allowedReason(),
                        "ALLOWED selection должна иметь allowedReason"
                ),
                selection.modelKey()
        );
    }

    private ModelRouteRequest syntheticRequest(
            UUID organizationId,
            UUID userId
    ) {
        return new ModelRouteRequest(
                organizationId,
                userId,
                PREVIEW_UUID,
                PREVIEW_UUID,
                PREVIEW_UUID,
                PREVIEW_SHA256,
                null,
                "x", // Smallest valid nonblank chat message.
                List.of(),
                Set.of(),
                envelopeService.additionalInputUnitUpperBound(false, Set.of())
        );
    }

    private static UUID resolveVisibleOrganization(
            UUID requested,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(requested, "organizationId не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");
        ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                currentUser,
                "Недостаточно прав для model policy preview"
        );

        if (ModelControlPlaneAccess.isTenantScopeRestricted(currentUser)
                && !currentUser.getOrganizationId().equals(requested)) {
            throw new ForbiddenOperationException(
                    "ADMIN может проверять model policy только своей организации"
            );
        }
        return requested;
    }

    private record AutomaticPreview(
            ModelRouteOutcome outcome,
            ModelRouteReason reason,
            String modelKey
    ) {
    }
}
