package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.domain.ModelRouteResult;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.ModelRouteDecisionResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.exception.ModelRouteDeniedException;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ModelRoutingService {

    private final OrganizationModelPolicyRepository policyRepository;
    private final ModelRouteDecisionRepository decisionRepository;
    private final RuntimeModelStatusService runtimeStatusService;
    private final AuditEventService audit;
    private final ModelRoutingSelectionPolicy selectionPolicy;
    private final ModelRoutingCostPolicy costPolicy;
    private final ModelRouteDecisionFactory decisionFactory;

    /**
     * Constructor signature is intentionally unchanged for Spring wiring and
     * the existing Mockito tests.
     */
    public ModelRoutingService(
            ModelCatalogRepository catalogRepository,
            OrganizationModelPolicyRepository policyRepository,
            ModelRouteDecisionRepository decisionRepository,
            RuntimeModelStatusService runtimeStatusService,
            AuditEventService audit
    ) {
        ModelCatalogRepository requiredCatalogRepository =
                Objects.requireNonNull(
                        catalogRepository,
                        "catalogRepository не должен быть null"
                );
        this.policyRepository = Objects.requireNonNull(
                policyRepository,
                "policyRepository не должен быть null"
        );
        this.decisionRepository = Objects.requireNonNull(
                decisionRepository,
                "decisionRepository не должен быть null"
        );
        this.runtimeStatusService = Objects.requireNonNull(
                runtimeStatusService,
                "runtimeStatusService не должен быть null"
        );
        this.audit = Objects.requireNonNull(
                audit,
                "audit не должен быть null"
        );
        this.selectionPolicy =
                new ModelRoutingSelectionPolicy(
                        requiredCatalogRepository
                );
        this.costPolicy =
                new ModelRoutingCostPolicy(
                        this.decisionRepository
                );
        this.decisionFactory = new ModelRouteDecisionFactory();
    }

    /**
     * V45 governance boundary.
     * <p>
     * Must run inside ChatTurnReservationService after ChatTurn idempotency
     * lookup and before USER/PROCESSING/quota/rate-limit reservation.
     */
    @Transactional(
            propagation = Propagation.MANDATORY,
            noRollbackFor = ModelRouteDeniedException.class
    )
    public ModelRouteResult decide(
            ModelRouteRequest request,
            SafeAiUserPrincipal currentUser,
            Instant now
    ) {
        validateIdentity(request, currentUser, now);

        Optional<ModelRouteDecision> replay =
                decisionRepository.findByRequest(
                        request.chatId(),
                        request.clientRequestId()
                );
        if (replay.isPresent()) {
            return decisionFactory.replayDecision(
                    replay.get(),
                    request
            );
        }

        RuntimeModelStatusResponse runtime =
                Objects.requireNonNull(
                        runtimeStatusService.current(),
                        "Runtime model status не должен быть null"
                );
        OrganizationModelPolicy policy = policyRepository
                .findLatest(request.organizationId())
                .orElse(null);
        boolean policyEnabled = policy != null && policy.enabled();

        ModelRoutingSelectionPolicy.Selection selection =
                selectionPolicy.selectCatalog(
                        request,
                        runtime,
                        policy,
                        policyEnabled,
                        now
                );

        if (selection.denialReason() != null) {
            throw persistDenied(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    selection.entry(),
                    selection.modelKey(),
                    selection.denialReason(),
                    null,
                    null,
                    null
            );
        }

        ModelCatalogEntry entry = selection.entry();
        String selectedModelKey = selection.modelKey();

        if (entry != null) {
            ModelRouteReason catalogPolicyDenial =
                    selectionPolicy.validateCatalogAndPolicy(
                            entry,
                            selectedModelKey,
                            runtime,
                            policy,
                            policyEnabled,
                            request.requiredCapabilities()
                    );
            if (catalogPolicyDenial != null) {
                throw persistDenied(
                        request,
                        currentUser,
                        now,
                        runtime,
                        policy,
                        entry,
                        selectedModelKey,
                        catalogPolicyDenial,
                        null,
                        null,
                        null
                );
            }
        }

        long estimatedInputTokens =
                costPolicy.estimateInputTokens(request);
        long estimatedOutputTokens =
                costPolicy.effectiveOutputLimit(
                        entry,
                        runtime,
                        policy,
                        policyEnabled
                );
        long effectiveInputLimit =
                costPolicy.effectiveInputLimit(
                        entry,
                        runtime,
                        policy,
                        policyEnabled
                );

        if (estimatedInputTokens > effectiveInputLimit) {
            throw persistDenied(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    entry,
                    selectedModelKey,
                    ModelRouteReason.INPUT_LIMIT_EXCEEDED,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    null
            );
        }

        ModelRoutingCostPolicy.PricingEstimate pricing =
                costPolicy.estimateCost(
                        entry,
                        runtime,
                        estimatedInputTokens,
                        estimatedOutputTokens
                );

        if (policyEnabled) {
            enforceRequestPricingPolicy(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    entry,
                    selectedModelKey,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing
            );
        }

        ModelRoutingCostPolicy.BudgetSnapshot budget =
                costPolicy.evaluateBudget(
                        request.organizationId(),
                        policy,
                        policyEnabled,
                        pricing.cost(),
                        now
                );

        if (budget.denialReason() != null) {
            throw persistDenied(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    entry,
                    selectedModelKey,
                    budget.denialReason(),
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing.cost(),
                    budget.monthlyCostKnown(),
                    budget.exceeded(),
                    budget
            );
        }

        ModelRouteDecisionFactory.DecisionDraft draft =
                new ModelRouteDecisionFactory.DecisionDraft(
                        entry,
                        selectedModelKey,
                        runtime.provider(),
                        runtime.model(),
                        estimatedInputTokens,
                        estimatedOutputTokens,
                        pricing.cost(),
                        pricing.complete(),
                        budget,
                        ModelRouteOutcome.ALLOWED,
                        selection.allowedReason()
                );

        ModelRouteDecision decision = decisionFactory.buildDecision(
                request,
                policy,
                draft,
                request.plannedTurnId(),
                now
        );
        decisionRepository.insert(decision);
        recordDecisionAudit(
                currentUser,
                decision,
                AuditEventType.MODEL_ROUTE_DECIDED
        );

        return decisionFactory.toResult(decision);
    }

    @Transactional(readOnly = true)
    public ModelRouteDecisionResponse findDecision(
            UUID decisionId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                decisionId,
                "decisionId не должен быть null"
        );
        ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                currentUser,
                "Недостаточно прав для model route evidence"
        );

        ModelRouteDecision decision = decisionRepository
                .findById(decisionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Model route decision не найден"
                        )
                );

        if (isCrossTenantDecision(
                currentUser,
                decision.organizationId()
        )) {
            throw new ResourceNotFoundException(
                    "Model route decision не найден"
            );
        }

        ModelRouteDecisionIntegrity.requireValid(decision);
        return ModelRouteDecisionResponse.from(decision);
    }

    private void enforceRequestPricingPolicy(
            ModelRouteRequest request,
            SafeAiUserPrincipal currentUser,
            Instant now,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            ModelCatalogEntry entry,
            String selectedModelKey,
            long estimatedInputTokens,
            long estimatedOutputTokens,
            ModelRoutingCostPolicy.PricingEstimate pricing
    ) {
        if (policy.requireCompletePricing() && !pricing.complete()) {
            throw persistDenied(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    entry,
                    selectedModelKey,
                    ModelRouteReason.PRICING_INCOMPLETE,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing.cost()
            );
        }

        if (policy.maxRequestCostUsd() == null) {
            return;
        }

        if (pricing.cost() == null) {
            throw persistDenied(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    entry,
                    selectedModelKey,
                    ModelRouteReason.PRICING_INCOMPLETE,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    null
            );
        }

        if (pricing.cost().compareTo(policy.maxRequestCostUsd()) > 0) {
            throw persistDenied(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    entry,
                    selectedModelKey,
                    ModelRouteReason.REQUEST_COST_LIMIT_EXCEEDED,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing.cost()
            );
        }
    }

    private ModelRouteDeniedException persistDenied(
            ModelRouteRequest request,
            SafeAiUserPrincipal currentUser,
            Instant now,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            ModelCatalogEntry entry,
            String selectedModelKey,
            ModelRouteReason reason,
            Long estimatedInputTokens,
            Long estimatedOutputTokens,
            BigDecimal estimatedCost
    ) {
        return persistDenied(
                request,
                currentUser,
                now,
                runtime,
                policy,
                entry,
                selectedModelKey,
                reason,
                estimatedInputTokens,
                estimatedOutputTokens,
                estimatedCost,
                true,
                false,
                ModelRoutingCostPolicy.BudgetSnapshot.none()
        );
    }

    private ModelRouteDeniedException persistDenied(
            ModelRouteRequest request,
            SafeAiUserPrincipal currentUser,
            Instant now,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            ModelCatalogEntry entry,
            String selectedModelKey,
            ModelRouteReason reason,
            Long estimatedInputTokens,
            Long estimatedOutputTokens,
            BigDecimal estimatedCost,
            boolean monthlyCostKnown,
            boolean budgetExceeded,
            ModelRoutingCostPolicy.BudgetSnapshot budget
    ) {
        boolean pricingComplete = entry != null
                ? entry.pricingComplete()
                : "FREE".equals(runtime.pricingStatus());

        ModelRouteDecisionFactory.DecisionDraft draft =
                new ModelRouteDecisionFactory.DecisionDraft(
                        entry,
                        selectedModelKey,
                        entry == null
                                ? runtime.provider()
                                : entry.provider(),
                        entry == null
                                ? runtime.model()
                                : entry.providerModelId(),
                        estimatedInputTokens,
                        estimatedOutputTokens,
                        estimatedCost,
                        pricingComplete,
                        budget.withFlags(
                                monthlyCostKnown,
                                budgetExceeded
                        ),
                        ModelRouteOutcome.DENIED,
                        reason
                );

        ModelRouteDecision decision = decisionFactory.buildDecision(
                request,
                policy,
                draft,
                null,
                now
        );
        decisionRepository.insert(decision);
        recordDecisionAudit(
                currentUser,
                decision,
                AuditEventType.MODEL_ROUTE_DENIED
        );

        return new ModelRouteDeniedException(
                decision.id(),
                reason,
                ModelRouteDecisionFactory.publicDenialMessage(
                        reason,
                        decision.id()
                )
        );
    }

    private void recordDecisionAudit(
            SafeAiUserPrincipal currentUser,
            ModelRouteDecision decision,
            AuditEventType eventType
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("decisionId", decision.id());
        details.put("chatId", decision.chatId());
        details.put("clientRequestId", decision.clientRequestId());
        details.put("outcome", decision.outcome());
        details.put("reason", decision.reason());
        details.put("decisionSha256", decision.decisionSha256());

        if (decision.chatTurnId() != null) {
            details.put("chatTurnId", decision.chatTurnId());
        }
        if (decision.selectedModelKey() != null) {
            details.put("modelKey", decision.selectedModelKey());
        }
        if (decision.selectedProvider() != null) {
            details.put("provider", decision.selectedProvider());
        }
        if (decision.selectedProviderModelId() != null) {
            details.put(
                    "providerModel",
                    decision.selectedProviderModelId()
            );
        }
        if (decision.selectedCatalogVersion() != null) {
            details.put(
                    "catalogVersion",
                    decision.selectedCatalogVersion()
            );
        }
        if (decision.policyVersion() != null) {
            details.put("policyVersion", decision.policyVersion());
        }
        if (decision.estimatedMaxCostUsd() != null) {
            details.put(
                    "estimatedMaxCostUsd",
                    decision.estimatedMaxCostUsd()
            );
        }

        details.put("budgetExceeded", decision.budgetExceeded());
        details.put("monthlyCostKnown", decision.monthlyCostKnown());

        audit.record(
                currentUser,
                decision.organizationId(),
                eventType,
                details
        );
    }

    private static void validateIdentity(
            ModelRouteRequest request,
            SafeAiUserPrincipal currentUser,
            Instant now
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
        Objects.requireNonNull(now, "now не должен быть null");

        if (!request.userId().equals(currentUser.getId())
                || !request.organizationId()
                .equals(currentUser.getOrganizationId())) {
            throw new ForbiddenOperationException(
                    "Model route principal identity mismatch"
            );
        }

        if (!request.requestContentHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "requestContentHash должен быть lowercase SHA-256"
            );
        }
    }

    private static boolean isCrossTenantDecision(
            SafeAiUserPrincipal currentUser,
            UUID decisionOrganizationId
    ) {
        return ModelControlPlaneAccess.isTenantScopeRestricted(currentUser)
                && !decisionOrganizationId.equals(
                currentUser.getOrganizationId()
        );
    }
}
