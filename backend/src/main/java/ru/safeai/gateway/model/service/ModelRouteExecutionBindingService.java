package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.service.ChatContentNormalizer;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;

import java.util.Objects;
import java.util.UUID;

/** Server-side issuer and verifier of DB-backed execution identities. */
@Service
public class ModelRouteExecutionBindingService {
    private final ModelRouteDecisionRepository repository;
    private final ChatContentNormalizer contentNormalizer;
    private final ModelRouteReservedRequestService reservedRequests;

    public ModelRouteExecutionBindingService(
            ModelRouteDecisionRepository repository,
            ChatContentNormalizer contentNormalizer,
            ModelRouteReservedRequestService reservedRequests
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.contentNormalizer = Objects.requireNonNull(contentNormalizer, "contentNormalizer");
        this.reservedRequests = Objects.requireNonNull(reservedRequests, "reservedRequests");
    }

    @Transactional(readOnly = true)
    public ModelRouteExecutionIdentity bind(
            UUID decisionId, UUID chatTurnId, UUID clientRequestId,
            UUID providerOperationId, AiChatRequest reserved,
            UUID knowledgeBaseId, KnowledgeMode knowledgeMode
    ) {
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(knowledgeMode, "knowledgeMode");
        ModelRouteDecision decision = readDecision(decisionId);
        String semantic = contentNormalizer.requestHash(
                reserved.userMessage(), knowledgeBaseId, knowledgeMode);
        if (!decision.requestContentHash().equals(semantic)) {
            throw new IllegalStateException("Reserved message does not match semantic route hash");
        }
        reservedRequests.requireSame(
                decisionId, chatTurnId, providerOperationId, reserved);
        return ModelRouteExecutionIdentity.bind(
                decision, chatTurnId, clientRequestId, providerOperationId,
                reserved, knowledgeBaseId, knowledgeMode);
    }

    /** Must run before execution-plan creation, attempt STARTED, and provider I/O. */
    @Transactional(readOnly = true)
    public void requireExecutable(
            ModelRouteExecutionIdentity identity,
            UUID chatTurnId,
            UUID modelRouteDecisionId,
            AiChatRequest reserved,
            AiChatRequest prepared,
            String targetProvider,
            String targetModel
    ) {
        Objects.requireNonNull(identity, "identity");
        if (!identity.chatTurnId().equals(chatTurnId)
                || !identity.decisionId().equals(modelRouteDecisionId)
                || !identity.provider().equals(targetProvider)
                || !identity.providerModelId().equals(targetModel)) {
            throw new IllegalStateException("Execution target or ChatTurn identity changed");
        }
        identity.requireSameDecision(readDecision(modelRouteDecisionId));
        reservedRequests.requireSame(
                modelRouteDecisionId, chatTurnId, identity.providerOperationId(), reserved);
        if (!contentNormalizer.requestHash(
                reserved.userMessage(), identity.knowledgeBaseId(), identity.knowledgeMode()
        ).equals(identity.semanticRequestHash())) {
            throw new IllegalStateException("Semantic request has changed before execution");
        }
        ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity, reserved, prepared);
    }

    @Transactional(readOnly = true)
    public boolean requiresSinglePhysicalAttempt(UUID decisionId) {
        ModelRouteDecision decision = readDecision(decisionId);
        if (decision.outcome() != ru.safeai.gateway.model.domain.ModelRouteOutcome.ALLOWED) {
            throw new IllegalStateException("Only ALLOWED route may execute");
        }
        return decision.budgetEnforcement()
                == ru.safeai.gateway.model.domain.BudgetEnforcement.HARD
                && decision.monthlyBudgetUsd() != null;
    }

    private ModelRouteDecision readDecision(UUID id) {
        Objects.requireNonNull(id, "modelRouteDecisionId");
        ModelRouteDecision decision = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Route decision not found"));
        ModelRouteDecisionIntegrity.requireValid(decision);
        return decision;
    }
}
