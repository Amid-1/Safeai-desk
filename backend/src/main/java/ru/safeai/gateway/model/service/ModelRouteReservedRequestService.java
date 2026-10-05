package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.service.ChatContentNormalizer;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.repository.ModelRouteRequestBindingRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Persisted append-only request identity and final prepared-request binding. */
@Service
public class ModelRouteReservedRequestService {

    private static final short PREPARED_BINDING_VERSION = 2;

    private final ModelRouteDecisionRepository decisions;
    private final ModelRouteRequestBindingRepository bindings;
    private final ChatContentNormalizer normalizer;
    private final Clock clock;

    public ModelRouteReservedRequestService(
            ModelRouteDecisionRepository decisions,
            ModelRouteRequestBindingRepository bindings,
            ChatContentNormalizer normalizer,
            Clock clock
    ) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Call after ChatTurn flush, in the SAME reservation transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void seal(
            UUID decisionId,
            UUID chatTurnId,
            UUID clientRequestId,
            AiChatRequest baseRequest,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(baseRequest, "baseRequest");
        Objects.requireNonNull(baseRequest.providerOperationId(), "providerOperationId");
        Objects.requireNonNull(knowledgeMode, "knowledgeMode");
        requireKnowledgeScopeShape(knowledgeMode, knowledgeBaseId);

        ModelRouteDecision decision = readDecision(decisionId);
        if (decision.outcome() != ModelRouteOutcome.ALLOWED
                || decision.decisionIntegrityVersion() != 3
                || decision.selectedCatalogEntryId() == null
                || !Objects.equals(decision.chatTurnId(), chatTurnId)
                || !decision.clientRequestId().equals(clientRequestId)
                || !decision.organizationId().equals(baseRequest.organizationId())
                || !decision.userId().equals(baseRequest.userId())
                || !decision.chatId().equals(baseRequest.chatId())
                || !Objects.equals(
                        decision.estimatedInputTokens(),
                        baseRequest.reservedInputTokens()
                )
                || !Objects.equals(
                        decision.estimatedOutputTokens(),
                        baseRequest.maxOutputTokens() == null
                                ? null
                                : baseRequest.maxOutputTokens().longValue()
                )
                || !ModelInputTokenEstimator.version().equals(
                        decision.inputAccountingVersion()
                )
                || !decision.requestContentHash().equals(
                        normalizer.requestHash(
                                baseRequest.userMessage(),
                                knowledgeBaseId,
                                knowledgeMode
                        )
                )
                || decision.additionalInputUnitUpperBound() == null
                || Math.addExact(
                        ModelInputTokenEstimator.estimateBaseRequest(baseRequest),
                        decision.additionalInputUnitUpperBound()
                ) != decision.estimatedInputTokens()) {
            throw new IllegalStateException(
                    "Cannot seal a base request different from routed evidence"
            );
        }

        int count = bindings.insertReserved(
                decisionId,
                chatTurnId,
                baseRequest.providerOperationId(),
                ModelRouteExecutionIdentity.baseSha256(baseRequest),
                DatabaseTimestampNormalizer.normalize(clock.instant())
        );
        if (count != 1) {
            throw new IllegalStateException("Reserved request was not sealed");
        }
    }

    @Transactional(readOnly = true)
    ModelRouteRequestBindingRepository.KnowledgeRagReceipt requireKnowledgeReceipt(
            UUID chatTurnId,
            UUID organizationId,
            UUID userId,
            UUID knowledgeBaseId
    ) {
        ModelRouteRequestBindingRepository.KnowledgeRagReceipt receipt =
                bindings.findKnowledgeRagReceipt(chatTurnId, knowledgeBaseId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Trusted Knowledge RAG context receipt is missing"
                        ));

        if (!receipt.chatTurnId().equals(chatTurnId)
                || !receipt.organizationId().equals(organizationId)
                || !receipt.userId().equals(userId)
                || !receipt.knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new IllegalStateException(
                    "Knowledge RAG receipt does not match governed execution identity"
            );
        }
        return receipt;
    }

    @Transactional
    void bindPrepared(
            ModelRouteExecutionIdentity identity,
            AiChatRequest reserved,
            AiChatRequest prepared
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(prepared, "prepared");

        String baseSha256 = ModelRouteExecutionIdentity.baseSha256(reserved);
        String preparedSha256 = ModelRouteExecutionIdentity.preparedSha256(prepared);
        long preparedInputUnits = ModelInputTokenEstimator.estimatePreparedRequest(prepared);

        UUID knowledgeBaseId = null;
        UUID retrievalRunId = null;
        String ragContextSha256 = null;

        if (identity.knowledgeMode().usesKnowledge()) {
            ModelRouteRequestBindingRepository.KnowledgeRagReceipt receipt =
                    requireKnowledgeReceipt(
                            identity.chatTurnId(),
                            identity.organizationId(),
                            identity.userId(),
                            identity.knowledgeBaseId()
                    );

            if (!Objects.equals(identity.retrievalRunId(), receipt.retrievalRunId())
                    || !Objects.equals(identity.ragContextSha256(), receipt.contextSha256())
                    || !Objects.equals(
                            identity.trustedPreparedRequestSha256(),
                            receipt.preparedRequestSha256()
                    )
                    || !preparedSha256.equals(receipt.preparedRequestSha256())) {
                throw new IllegalStateException(
                        "Prepared request does not match durable Knowledge RAG evidence"
                );
            }

            knowledgeBaseId = receipt.knowledgeBaseId();
            retrievalRunId = receipt.retrievalRunId();
            ragContextSha256 = receipt.contextSha256();
        } else if (identity.retrievalRunId() != null
                || identity.ragContextSha256() != null
                || identity.trustedPreparedRequestSha256() != null) {
            throw new IllegalStateException(
                    "GENERAL execution cannot carry Knowledge RAG evidence"
            );
        }

        ModelRouteRequestBindingRepository.PreparedBinding expected =
                new ModelRouteRequestBindingRepository.PreparedBinding(
                        identity.decisionId(),
                        identity.chatTurnId(),
                        identity.providerOperationId(),
                        baseSha256,
                        preparedSha256,
                        ModelInputTokenEstimator.version(),
                        preparedInputUnits,
                        DatabaseTimestampNormalizer.normalize(clock.instant()),
                        knowledgeBaseId,
                        retrievalRunId,
                        ragContextSha256,
                        PREPARED_BINDING_VERSION
                );

        bindings.insertPreparedIfAbsent(expected);

        ModelRouteRequestBindingRepository.PreparedBinding persisted =
                bindings.findPrepared(identity.decisionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Prepared request receipt was not persisted"
                        ));

        requireEquivalentPreparedBinding(expected, persisted);
    }

    @Transactional(readOnly = true)
    public void requireSame(
            UUID decisionId,
            UUID chatTurnId,
            UUID providerOperationId,
            AiChatRequest request
    ) {
        Objects.requireNonNull(request, "request");
        String expectedHash = ModelRouteExecutionIdentity.baseSha256(request);
        String persistedHash = bindings.findReservedHash(
                        decisionId,
                        chatTurnId,
                        providerOperationId
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Reserved request seal is missing"
                ));
        if (!expectedHash.equals(persistedHash)) {
            throw new IllegalStateException(
                    "Reserved request differs from persisted request seal"
            );
        }
    }

    private ModelRouteDecision readDecision(UUID decisionId) {
        ModelRouteDecision decision = decisions.findById(decisionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Model route decision not found: " + decisionId
                ));
        ModelRouteDecisionIntegrity.requireValid(decision);
        return decision;
    }

    private static void requireKnowledgeScopeShape(
            KnowledgeMode knowledgeMode,
            UUID knowledgeBaseId
    ) {
        if (knowledgeMode.usesKnowledge() && knowledgeBaseId == null) {
            throw new IllegalArgumentException(
                    "knowledgeBaseId обязателен для KnowledgeMode"
            );
        }
        if (!knowledgeMode.usesKnowledge() && knowledgeBaseId != null) {
            throw new IllegalArgumentException(
                    "GENERAL KnowledgeMode не должен содержать knowledgeBaseId"
            );
        }
    }

    private static void requireEquivalentPreparedBinding(
            ModelRouteRequestBindingRepository.PreparedBinding expected,
            ModelRouteRequestBindingRepository.PreparedBinding persisted
    ) {
        if (!expected.modelRouteDecisionId().equals(persisted.modelRouteDecisionId())
                || !expected.chatTurnId().equals(persisted.chatTurnId())
                || !expected.providerOperationId().equals(persisted.providerOperationId())
                || !expected.baseRequestSha256().equals(persisted.baseRequestSha256())
                || !expected.preparedRequestSha256().equals(persisted.preparedRequestSha256())
                || !expected.inputAccountingVersion().equals(persisted.inputAccountingVersion())
                || expected.preparedInputUnits() != persisted.preparedInputUnits()
                || !Objects.equals(expected.knowledgeBaseId(), persisted.knowledgeBaseId())
                || !Objects.equals(expected.retrievalRunId(), persisted.retrievalRunId())
                || !Objects.equals(expected.ragContextSha256(), persisted.ragContextSha256())
                || persisted.bindingVersion() != PREPARED_BINDING_VERSION) {
            throw new IllegalStateException(
                    "Prepared request differs from immutable persisted receipt"
            );
        }
    }
}
