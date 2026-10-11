package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.model.spi.ModelRequestContentHashPort;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.ModelRouteRequestBindingRepository;

import java.util.Objects;
import java.util.UUID;

/** Server-side issuer and verifier of DB-backed execution identities. */
@Service
public class ModelRouteExecutionBindingService {

    private final ModelRouteDecisionRepository repository;
    private final ModelRequestContentHashPort contentNormalizer;
    private final ModelRouteReservedRequestService reservedRequests;

    public ModelRouteExecutionBindingService(
            ModelRouteDecisionRepository repository,
            ModelRequestContentHashPort contentNormalizer,
            ModelRouteReservedRequestService reservedRequests
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.contentNormalizer = Objects.requireNonNull(
                contentNormalizer,
                "contentNormalizer"
        );
        this.reservedRequests = Objects.requireNonNull(
                reservedRequests,
                "reservedRequests"
        );
    }

    /**
     * Issues an execution identity only from immutable route/base-request state.
     * For Knowledge routes the durable context receipt must already have been
     * committed by KnowledgeRagService.prepare().
     */
    @Transactional(readOnly = true)
    public ModelRouteExecutionIdentity bind(
            UUID decisionId,
            UUID chatTurnId,
            UUID clientRequestId,
            UUID providerOperationId,
            AiChatRequest reserved,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(knowledgeMode, "knowledgeMode");

        ModelRouteDecision decision = readDecision(decisionId);
        String semantic = contentNormalizer.requestHash(
                reserved.userMessage(),
                knowledgeBaseId,
                knowledgeMode
        );
        if (!decision.requestContentHash().equals(semantic)) {
            throw new IllegalStateException(
                    "Reserved message does not match semantic route hash"
            );
        }

        reservedRequests.requireSame(
                decisionId,
                chatTurnId,
                providerOperationId,
                reserved
        );

        UUID retrievalRunId = null;
        String ragContextSha256 = null;
        String trustedPreparedRequestSha256 = null;

        if (knowledgeMode.usesKnowledge()) {
            if (knowledgeBaseId == null) {
                throw new IllegalStateException(
                        "Knowledge route requires knowledgeBaseId"
                );
            }
            ModelRouteRequestBindingRepository.KnowledgeRagReceipt receipt =
                    reservedRequests.requireKnowledgeReceipt(
                            chatTurnId,
                            decision.organizationId(),
                            decision.userId(),
                            knowledgeBaseId
                    );
            retrievalRunId = receipt.retrievalRunId();
            ragContextSha256 = receipt.contextSha256();
            trustedPreparedRequestSha256 = receipt.preparedRequestSha256();
        } else if (knowledgeBaseId != null) {
            throw new IllegalStateException(
                    "GENERAL route cannot carry knowledgeBaseId"
            );
        }

        return ModelRouteExecutionIdentity.bind(
                decision,
                chatTurnId,
                clientRequestId,
                providerOperationId,
                reserved,
                knowledgeBaseId,
                knowledgeMode,
                retrievalRunId,
                ragContextSha256,
                trustedPreparedRequestSha256
        );
    }

    /**
     * Must run before ExecutionPlan creation, attempt STARTED and provider I/O.
     * This method re-verifies all DB evidence so a caller cannot manufacture a
     * trusted execution identity in Java.
     */
    @Transactional
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
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(prepared, "prepared");

        if (!identity.chatTurnId().equals(chatTurnId)
                || !identity.decisionId().equals(modelRouteDecisionId)
                || !identity.provider().equals(targetProvider)
                || !identity.providerModelId().equals(targetModel)) {
            throw new IllegalStateException(
                    "Execution target or ChatTurn identity changed"
            );
        }

        ModelRouteDecision decision = readDecision(modelRouteDecisionId);
        identity.requireSameDecision(decision);

        reservedRequests.requireSame(
                modelRouteDecisionId,
                chatTurnId,
                identity.providerOperationId(),
                reserved
        );

        if (!contentNormalizer.requestHash(
                reserved.userMessage(),
                identity.knowledgeBaseId(),
                identity.knowledgeMode()
        ).equals(identity.semanticRequestHash())) {
            throw new IllegalStateException(
                    "Semantic request has changed before execution"
            );
        }

        ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity,
                reserved,
                prepared
        );

        reservedRequests.bindPrepared(
                identity,
                reserved,
                prepared
        );
    }

    @Transactional(readOnly = true)
    public boolean requiresSinglePhysicalAttempt(UUID decisionId) {
        ModelRouteDecision decision = readDecision(decisionId);
        if (decision.outcome() != ModelRouteOutcome.ALLOWED) {
            throw new IllegalStateException(
                    "Only ALLOWED route may execute"
            );
        }
        return repository.requiresSinglePhysicalAttempt(decisionId);
    }

    private ModelRouteDecision readDecision(UUID id) {
        Objects.requireNonNull(id, "modelRouteDecisionId");
        ModelRouteDecision decision = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Route decision not found: " + id
                ));
        ModelRouteDecisionIntegrity.requireValid(decision);
        return decision;
    }
}
