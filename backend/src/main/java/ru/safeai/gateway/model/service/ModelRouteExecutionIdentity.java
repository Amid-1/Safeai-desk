package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.execution.AiExecutionIdentity;
import ru.safeai.gateway.ai.input.AiRequestFingerprint;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable execution capability issued from a DB-verified ALLOWED decision.
 *
 * <p>For Knowledge routes V64 additionally binds the exact retrieval run,
 * exact assembled context hash and exact prepared-request hash written by the
 * Knowledge layer before provider I/O. The public constructor remains usable
 * for source compatibility, but an identity without durable Knowledge
 * provenance is rejected by the execution guard.</p>
 */
public record ModelRouteExecutionIdentity(
        UUID decisionId,
        UUID catalogEntryId,
        int catalogVersion,
        String selectedModelKey,
        UUID organizationId,
        UUID userId,
        UUID chatId,
        UUID chatTurnId,
        UUID clientRequestId,
        UUID providerOperationId,
        String semanticRequestHash,
        String baseRequestSha256,
        String inputAccountingVersion,
        long reservedInputUnits,
        int maxOutputUnits,
        String provider,
        String providerModelId,
        UUID knowledgeBaseId,
        KnowledgeMode knowledgeMode,
        UUID retrievalRunId,
        String ragContextSha256,
        String trustedPreparedRequestSha256
) implements AiExecutionIdentity {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ModelRouteExecutionIdentity {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(catalogEntryId, "catalogEntryId");
        Objects.requireNonNull(selectedModelKey, "selectedModelKey");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(chatId, "chatId");
        Objects.requireNonNull(chatTurnId, "chatTurnId");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(providerOperationId, "providerOperationId");
        Objects.requireNonNull(semanticRequestHash, "semanticRequestHash");
        Objects.requireNonNull(baseRequestSha256, "baseRequestSha256");
        Objects.requireNonNull(inputAccountingVersion, "inputAccountingVersion");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerModelId, "providerModelId");
        Objects.requireNonNull(knowledgeMode, "knowledgeMode");

        if (!SHA256.matcher(semanticRequestHash).matches()
                || !SHA256.matcher(baseRequestSha256).matches()
                || reservedInputUnits < 0L
                || catalogVersion <= 0
                || selectedModelKey.isBlank()
                || maxOutputUnits <= 0
                || !ModelInputTokenEstimator.version().equals(inputAccountingVersion)) {
            throw new IllegalStateException("Invalid governed execution identity");
        }

        if (knowledgeMode.usesKnowledge()) {
            Objects.requireNonNull(
                    knowledgeBaseId,
                    "knowledgeBaseId обязателен для Knowledge execution identity"
            );
            boolean noProvenance = retrievalRunId == null
                    && ragContextSha256 == null
                    && trustedPreparedRequestSha256 == null;
            boolean completeProvenance = retrievalRunId != null
                    && isSha256(ragContextSha256)
                    && isSha256(trustedPreparedRequestSha256);
            if (!noProvenance && !completeProvenance) {
                throw new IllegalStateException(
                        "Knowledge execution provenance must be absent or complete"
                );
            }
        } else if (knowledgeBaseId != null
                || retrievalRunId != null
                || ragContextSha256 != null
                || trustedPreparedRequestSha256 != null) {
            throw new IllegalStateException(
                    "GENERAL execution identity cannot carry Knowledge provenance"
            );
        }
    }

    /**
     * Source-compatible constructor for V53/V62 callers. New Knowledge
     * production identities are issued by {@link ModelRouteExecutionBindingService}
     * with durable V64 provenance.
     */
    public ModelRouteExecutionIdentity(
            UUID decisionId,
            UUID catalogEntryId,
            int catalogVersion,
            String selectedModelKey,
            UUID organizationId,
            UUID userId,
            UUID chatId,
            UUID chatTurnId,
            UUID clientRequestId,
            UUID providerOperationId,
            String semanticRequestHash,
            String baseRequestSha256,
            String inputAccountingVersion,
            long reservedInputUnits,
            int maxOutputUnits,
            String provider,
            String providerModelId,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
        this(
                decisionId,
                catalogEntryId,
                catalogVersion,
                selectedModelKey,
                organizationId,
                userId,
                chatId,
                chatTurnId,
                clientRequestId,
                providerOperationId,
                semanticRequestHash,
                baseRequestSha256,
                inputAccountingVersion,
                reservedInputUnits,
                maxOutputUnits,
                provider,
                providerModelId,
                knowledgeBaseId,
                knowledgeMode,
                null,
                null,
                null
        );
    }

    static ModelRouteExecutionIdentity bind(
            ModelRouteDecision decision,
            UUID chatTurnId,
            UUID clientRequestId,
            UUID providerOperationId,
            AiChatRequest reserved,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode,
            UUID retrievalRunId,
            String ragContextSha256,
            String trustedPreparedRequestSha256
    ) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reserved, "reserved");
        ModelRouteDecisionIntegrity.requireValid(decision);
        if (decision.outcome() != ModelRouteOutcome.ALLOWED
                || decision.decisionIntegrityVersion() != 3
                || decision.selectedCatalogEntryId() == null
                || decision.selectedCatalogVersion() == null
                || decision.selectedModelKey() == null
                || decision.selectedProvider() == null
                || decision.selectedProviderModelId() == null
                || decision.chatTurnId() == null
                || decision.estimatedInputTokens() == null
                || decision.estimatedOutputTokens() == null
                || !ModelInputTokenEstimator.version().equals(decision.inputAccountingVersion())) {
            throw new IllegalStateException(
                    "Decision is not executable under strict v3 governance"
            );
        }
        if (!decision.chatTurnId().equals(chatTurnId)
                || !decision.clientRequestId().equals(clientRequestId)
                || !decision.organizationId().equals(reserved.organizationId())
                || !decision.userId().equals(reserved.userId())
                || !decision.chatId().equals(reserved.chatId())
                || !providerOperationId.equals(reserved.providerOperationId())
                || !Objects.equals(
                        reserved.reservedInputTokens(),
                        decision.estimatedInputTokens()
                )
                || reserved.maxOutputTokens() == null
                || differentLong(
                        decision.estimatedOutputTokens(),
                        reserved.maxOutputTokens()
                )) {
            throw new IllegalStateException(
                    "Decision, ChatTurn and reserved request do not match"
            );
        }

        long base = ModelInputTokenEstimator.estimateBaseRequest(reserved);
        long expected = Math.addExact(
                base,
                decision.additionalInputUnitUpperBound()
        );
        if (expected != decision.estimatedInputTokens()) {
            throw new IllegalStateException(
                    "Reserved input does not match persisted accounting evidence"
            );
        }

        return new ModelRouteExecutionIdentity(
                decision.id(),
                decision.selectedCatalogEntryId(),
                decision.selectedCatalogVersion(),
                decision.selectedModelKey(),
                decision.organizationId(),
                decision.userId(),
                decision.chatId(),
                chatTurnId,
                clientRequestId,
                providerOperationId,
                decision.requestContentHash(),
                baseSha256(reserved),
                decision.inputAccountingVersion(),
                expected,
                Math.toIntExact(decision.estimatedOutputTokens()),
                decision.selectedProvider(),
                decision.selectedProviderModelId(),
                knowledgeBaseId,
                knowledgeMode,
                retrievalRunId,
                ragContextSha256,
                trustedPreparedRequestSha256
        );
    }

    void requireSameDecision(ModelRouteDecision decision) {
        ModelRouteDecisionIntegrity.requireValid(decision);
        if (decision.outcome() != ModelRouteOutcome.ALLOWED
                || decision.decisionIntegrityVersion() != 3
                || decision.selectedCatalogEntryId() == null
                || decision.selectedCatalogVersion() == null
                || !decision.id().equals(decisionId)
                || !Objects.equals(decision.selectedCatalogEntryId(), catalogEntryId)
                || differentInteger(decision.selectedCatalogVersion(), catalogVersion)
                || !Objects.equals(decision.selectedModelKey(), selectedModelKey)
                || !decision.organizationId().equals(organizationId)
                || !decision.userId().equals(userId)
                || !decision.chatId().equals(chatId)
                || !Objects.equals(decision.chatTurnId(), chatTurnId)
                || !decision.clientRequestId().equals(clientRequestId)
                || !decision.requestContentHash().equals(semanticRequestHash)
                || differentLong(decision.estimatedInputTokens(), reservedInputUnits)
                || differentLong(decision.estimatedOutputTokens(), maxOutputUnits)
                || !Objects.equals(decision.inputAccountingVersion(), inputAccountingVersion)
                || !Objects.equals(decision.selectedProvider(), provider)
                || !Objects.equals(decision.selectedProviderModelId(), providerModelId)) {
            throw new IllegalStateException(
                    "Execution identity does not match persisted route decision"
            );
        }
    }

    void requireSameBase(AiChatRequest request) {
        if (!organizationId.equals(request.organizationId())
                || !userId.equals(request.userId())
                || !chatId.equals(request.chatId())
                || !providerOperationId.equals(request.providerOperationId())
                || differentLong(request.reservedInputTokens(), reservedInputUnits)
                || differentInteger(request.maxOutputTokens(), maxOutputUnits)
                || !baseRequestSha256.equals(baseSha256(request))) {
            throw new IllegalStateException(
                    "Reserved AI request identity was replaced"
            );
        }
    }

    void requirePreparedBase(AiChatRequest reserved, AiChatRequest prepared) {
        if (!organizationId.equals(prepared.organizationId())
                || !userId.equals(prepared.userId())
                || !chatId.equals(prepared.chatId())
                || !providerOperationId.equals(prepared.providerOperationId())
                || differentLong(prepared.reservedInputTokens(), reservedInputUnits)
                || differentInteger(prepared.maxOutputTokens(), maxOutputUnits)
                || !reserved.userMessage().equals(prepared.userMessage())
                || !reserved.history().equals(prepared.history())) {
            throw new IllegalStateException(
                    "Prepared request has a different immutable base identity"
            );
        }

        if (knowledgeMode.usesKnowledge()) {
            requireAppendOnly(
                    reserved.systemInstructions(),
                    prepared.systemInstructions()
            );
            requireAppendOnly(
                    reserved.developerInstructions(),
                    prepared.developerInstructions()
            );
        } else if (!Objects.equals(
                reserved.systemInstructions(),
                prepared.systemInstructions()
        ) || !Objects.equals(
                reserved.developerInstructions(),
                prepared.developerInstructions()
        )) {
            throw new IllegalStateException(
                    "Non-RAG route changed instructions"
            );
        }
    }

    void requireTrustedPreparedProvenance(AiChatRequest prepared) {
        if (!knowledgeMode.usesKnowledge()) {
            if (retrievalRunId != null
                    || ragContextSha256 != null
                    || trustedPreparedRequestSha256 != null) {
                throw new IllegalStateException(
                        "GENERAL identity unexpectedly carries Knowledge provenance"
                );
            }
            return;
        }

        if (retrievalRunId == null
                || !isSha256(ragContextSha256)
                || !isSha256(trustedPreparedRequestSha256)) {
            throw new IllegalStateException(
                    "Knowledge execution identity lacks durable RAG provenance"
            );
        }

        String actualPreparedSha256 = preparedSha256(prepared);
        if (!trustedPreparedRequestSha256.equals(actualPreparedSha256)) {
            throw new IllegalStateException(
                    "Prepared request differs from the trusted Knowledge RAG receipt"
            );
        }
    }

    private static void requireAppendOnly(String base, String actual) {
        if (base == null) {
            if (actual == null || actual.isBlank()) {
                throw new IllegalStateException(
                        "Knowledge preparation must materialize a nonblank trusted instruction block"
                );
            }
            return;
        }
        if (actual == null
                || !(actual.equals(base) || actual.startsWith(base + "\n\n"))) {
            throw new IllegalStateException(
                    "RAG replaced the original instruction prefix"
            );
        }
    }

    private static boolean differentLong(Long value, long expected) {
        return value == null || value != expected;
    }

    private static boolean differentInteger(Integer value, int expected) {
        return value == null || value != expected;
    }

    static String preparedSha256(AiChatRequest request) {
        return AiRequestFingerprint.preparedSha256(request);
    }

    static String baseSha256(AiChatRequest request) {
        return AiRequestFingerprint.baseSha256(request);
    }

    private static boolean isSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }
}
