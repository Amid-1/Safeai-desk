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
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.dto.ModelRouteDecisionResponse;
import ru.safeai.gateway.model.exception.ModelRouteDeniedException;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;

import java.math.BigDecimal;
import java.time.Clock;
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
    private final Clock clock;

    public ModelRoutingService(
            ModelCatalogRepository catalogRepository,
            OrganizationModelPolicyRepository policyRepository,
            ModelRouteDecisionRepository decisionRepository,
            RuntimeModelStatusService runtimeStatusService,
            AuditEventService audit,
            Clock clock
    ) {
        ModelCatalogRepository requiredCatalogRepository = Objects.requireNonNull(
                catalogRepository,
                "catalogRepository не должен быть null"
        );
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository не должен быть null");
        this.decisionRepository = Objects.requireNonNull(decisionRepository, "decisionRepository не должен быть null");
        this.runtimeStatusService = Objects.requireNonNull(runtimeStatusService, "runtimeStatusService не должен быть null");
        this.audit = Objects.requireNonNull(audit, "audit не должен быть null");
        this.clock = Objects.requireNonNull(clock, "clock не должен быть null");
        this.selectionPolicy = new ModelRoutingSelectionPolicy(requiredCatalogRepository);
        this.costPolicy = new ModelRoutingCostPolicy(this.decisionRepository);
        this.decisionFactory = new ModelRouteDecisionFactory();
    }

    /**
     * Deterministic governance boundary. The effective-time instant is owned by
     * this service and cannot be supplied by a caller.
     */
    @Transactional(
            propagation = Propagation.MANDATORY,
            noRollbackFor = ModelRouteDeniedException.class
    )
    public ModelRouteResult decide(
            ModelRouteRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        validateIdentity(request, currentUser);
        Instant now = clock.instant();

        Optional<ModelRouteDecision> replay = decisionRepository.findByRequest(
                request.chatId(),
                request.clientRequestId()
        );
        if (replay.isPresent()) {
            return decisionFactory.replayDecision(replay.get(), request);
        }

        RuntimeModelStatusResponse runtime = Objects.requireNonNull(
                runtimeStatusService.current(),
                "Runtime model status не должен быть null"
        );
        OrganizationModelPolicy policy = policyRepository
                .findLatest(request.organizationId())
                .orElse(null);
        boolean policyEnabled = policy != null && policy.enabled();

        ModelRoutingSelectionPolicy.Selection selection = selectionPolicy.selectCatalog(
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
                    selection,
                    selection.denialReason(),
                    null,
                    null,
                    null,
                    null
            );
        }

        ModelCatalogEntry entry = selection.entry();
        if (entry != null) {
            ModelRouteReason catalogPolicyDenial = selectionPolicy.validateCatalogAndPolicy(
                    entry,
                    selection.modelKey(),
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
                        selection,
                        catalogPolicyDenial,
                        null,
                        null,
                        null,
                        null
                );
            }
        }

        long estimatedInputTokens = costPolicy.estimateInputTokens(request);
        long estimatedOutputTokens = costPolicy.effectiveOutputLimit(
                entry,
                runtime,
                policy,
                policyEnabled
        );
        long effectiveInputLimit = costPolicy.effectiveInputLimit(
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
                    selection,
                    ModelRouteReason.INPUT_LIMIT_EXCEEDED,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    null,
                    null
            );
        }

        ModelRoutingCostPolicy.PricingEstimate pricing = costPolicy.estimateCost(
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
                    selection,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing
            );
        }

        ModelRoutingCostPolicy.BudgetSnapshot budget = costPolicy.evaluateBudget(
                request.organizationId(),
                policy,
                policyEnabled,
                pricing,
                now
        );

        if (budget.denialReason() != null) {
            throw persistDenied(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    selection,
                    budget.denialReason(),
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing,
                    budget
            );
        }

        ModelRouteDecisionFactory.DecisionDraft draft =
                new ModelRouteDecisionFactory.DecisionDraft(
                        entry,
                        selection.modelKey(),
                        selection.selectedProvider(),
                        selection.selectedProviderModelId(),
                        estimatedInputTokens,
                        estimatedOutputTokens,
                        pricing.cost(),
                        pricing.complete(),
                        budget,
                        ModelRouteOutcome.ALLOWED,
                        Objects.requireNonNull(
                                selection.allowedReason(),
                                "ALLOWED selection должна иметь allowedReason"
                        )
                );

        ModelRouteDecision decision = decisionFactory.buildDecision(
                request,
                policy,
                draft,
                request.plannedTurnId(),
                now
        );
        decisionRepository.insert(decision);
        recordDecisionAudit(currentUser, decision, AuditEventType.MODEL_ROUTE_DECIDED);
        return decisionFactory.toResult(decision);
    }

    /**
     * Evaluates the deferred V45 exact-turn invariant before reservation
     * performs its only external side effect (Redis rate-limit reservation).
     *
     * <p>Must be invoked from the surrounding reservation transaction.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateAllowedTurnLinkBeforeExternalSideEffects() {
        decisionRepository.validateAllowedTurnLinkBeforeExternalSideEffects();
    }

    @Transactional(readOnly = true)
    public ModelRouteDecisionResponse findDecision(
            UUID decisionId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(decisionId, "decisionId не должен быть null");
        ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                currentUser,
                "Недостаточно прав для model route evidence"
        );

        ModelRouteDecision decision = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Model route decision не найден"
                ));

        if (isCrossTenantDecision(currentUser, decision.organizationId())) {
            throw new ResourceNotFoundException("Model route decision не найден");
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
            ModelRoutingSelectionPolicy.Selection selection,
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
                    selection,
                    ModelRouteReason.PRICING_INCOMPLETE,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing,
                    null
            );
        }

        if (policy.maxRequestCostUsd() == null) {
            return;
        }

        // A request cost cap is meaningful only against complete pricing.
        if (!pricing.complete() || pricing.cost() == null) {
            throw persistDenied(
                    request,
                    currentUser,
                    now,
                    runtime,
                    policy,
                    selection,
                    ModelRouteReason.PRICING_INCOMPLETE,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing,
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
                    selection,
                    ModelRouteReason.REQUEST_COST_LIMIT_EXCEEDED,
                    estimatedInputTokens,
                    estimatedOutputTokens,
                    pricing,
                    null
            );
        }
    }

    private ModelRouteDeniedException persistDenied(
            ModelRouteRequest request,
            SafeAiUserPrincipal currentUser,
            Instant now,
            RuntimeModelStatusResponse runtime,
            OrganizationModelPolicy policy,
            ModelRoutingSelectionPolicy.Selection selection,
            ModelRouteReason reason,
            Long estimatedInputTokens,
            Long estimatedOutputTokens,
            ModelRoutingCostPolicy.PricingEstimate pricing,
            ModelRoutingCostPolicy.BudgetSnapshot evaluatedBudget
    ) {
        ModelRoutingCostPolicy.BudgetSnapshot budget = evaluatedBudget == null
                ? ModelRoutingCostPolicy.BudgetSnapshot.none()
                : evaluatedBudget;

        boolean pricingComplete;
        BigDecimal estimatedCost;
        if (pricing != null) {
            pricingComplete = pricing.complete();
            estimatedCost = pricing.cost();
        } else if (selection.entry() != null) {
            pricingComplete = selection.entry().pricingComplete();
            estimatedCost = null;
        } else if (selection.selectedProvider() != null
                && selection.selectedProviderModelId() != null
                && "FREE".equals(runtime.pricingStatus())) {
            pricingComplete = true;
            estimatedCost = null;
        } else {
            pricingComplete = false;
            estimatedCost = null;
        }

        ModelRouteDecisionFactory.DecisionDraft draft =
                new ModelRouteDecisionFactory.DecisionDraft(
                        selection.entry(),
                        selection.modelKey(),
                        selection.selectedProvider(),
                        selection.selectedProviderModelId(),
                        estimatedInputTokens,
                        estimatedOutputTokens,
                        estimatedCost,
                        pricingComplete,
                        budget,
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
        recordDecisionAudit(currentUser, decision, AuditEventType.MODEL_ROUTE_DENIED);

        return new ModelRouteDeniedException(
                decision.id(),
                reason,
                ModelRouteDecisionFactory.publicDenialMessage(reason, decision.id())
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
        details.put("decisionIntegrityVersion", decision.decisionIntegrityVersion());
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
            details.put("providerModel", decision.selectedProviderModelId());
        }
        if (decision.selectedCatalogVersion() != null) {
            details.put("catalogVersion", decision.selectedCatalogVersion());
        }
        if (decision.policyVersion() != null) {
            details.put("policyVersion", decision.policyVersion());
        }
        if (decision.estimatedMaxCostUsd() != null) {
            details.put("estimatedMaxCostUsd", decision.estimatedMaxCostUsd().toPlainString());
        }

        details.put("budgetExceeded", decision.budgetExceeded());
        details.put("monthlyCostKnown", decision.monthlyCostKnown());
        details.put("monthlyCostState", decision.monthlyCostState());

        audit.record(
                currentUser,
                decision.organizationId(),
                eventType,
                details
        );
    }

    private static void validateIdentity(
            ModelRouteRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        Objects.requireNonNull(currentUser, "currentUser не должен быть null");

        if (!request.userId().equals(currentUser.getId())
                || !request.organizationId().equals(currentUser.getOrganizationId())) {
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
                && !decisionOrganizationId.equals(currentUser.getOrganizationId());
    }
}
