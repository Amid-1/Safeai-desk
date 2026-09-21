package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * The immutable receipt for one DB-verified ALLOWED decision and its exact
 * pre-RAG request. Never derive an identity from the prepared request.
 * The public record constructor validates shape; execution remains untrusted
 * until ModelRouteExecutionBindingService verifies persisted decision evidence.
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
        KnowledgeMode knowledgeMode
) {
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
        if (!semanticRequestHash.matches("[0-9a-f]{64}")
                || !baseRequestSha256.matches("[0-9a-f]{64}")
                || reservedInputUnits < 0
                || catalogVersion <= 0
                || selectedModelKey.isBlank()
                || maxOutputUnits <= 0
                || !AiInputUnitEstimator.VERSION.equals(inputAccountingVersion)
                || (knowledgeMode.usesKnowledge() && knowledgeBaseId == null)
                || (!knowledgeMode.usesKnowledge() && knowledgeBaseId != null)) {
            throw new IllegalStateException("Invalid governed execution identity");
        }
    }

    static ModelRouteExecutionIdentity bind(
            ModelRouteDecision decision,
            UUID chatTurnId,
            UUID clientRequestId,
            UUID providerOperationId,
            AiChatRequest reserved,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
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
                || !AiInputUnitEstimator.VERSION.equals(decision.inputAccountingVersion())) {
            throw new IllegalStateException("Decision is not executable under strict v3 governance");
        }
        if (!decision.chatTurnId().equals(chatTurnId)
                || !decision.clientRequestId().equals(clientRequestId)
                || !decision.organizationId().equals(reserved.organizationId())
                || !decision.userId().equals(reserved.userId())
                || !decision.chatId().equals(reserved.chatId())
                || !providerOperationId.equals(reserved.providerOperationId())
                || !Objects.equals(decision.estimatedInputTokens(), reserved.reservedInputTokens())
                || !Objects.equals(decision.estimatedOutputTokens(),
                        reserved.maxOutputTokens() == null
                                ? null : reserved.maxOutputTokens().longValue())) {
            throw new IllegalStateException("Decision, ChatTurn and reserved request do not match");
        }
        // Enforce exact accounting estimate, not merely the copied cap.
        long base = AiInputUnitEstimator.estimateBaseRequest(
                reserved.userMessage(), reserved.history());
        long expected = Math.addExact(base, decision.additionalInputUnitUpperBound());
        if (expected != decision.estimatedInputTokens()) {
            throw new IllegalStateException("Reserved input does not match persisted accounting evidence");
        }
        return new ModelRouteExecutionIdentity(
                decision.id(), decision.selectedCatalogEntryId(),
                decision.selectedCatalogVersion(), decision.selectedModelKey(),
                decision.organizationId(), decision.userId(),
                decision.chatId(), chatTurnId, clientRequestId, providerOperationId,
                decision.requestContentHash(), baseSha256(reserved),
                decision.inputAccountingVersion(), expected,
                Math.toIntExact(decision.estimatedOutputTokens()),
                decision.selectedProvider(), decision.selectedProviderModelId(),
                knowledgeBaseId, knowledgeMode
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
                || !Objects.equals(decision.selectedCatalogVersion(), catalogVersion)
                || !Objects.equals(decision.selectedModelKey(), selectedModelKey)
                || !decision.organizationId().equals(organizationId)
                || !decision.userId().equals(userId)
                || !decision.chatId().equals(chatId)
                || !Objects.equals(decision.chatTurnId(), chatTurnId)
                || !decision.clientRequestId().equals(clientRequestId)
                || !decision.requestContentHash().equals(semanticRequestHash)
                || !Objects.equals(decision.estimatedInputTokens(), reservedInputUnits)
                || !Objects.equals(decision.estimatedOutputTokens(), (long) maxOutputUnits)
                || !Objects.equals(decision.inputAccountingVersion(), inputAccountingVersion)
                || !Objects.equals(decision.selectedProvider(), provider)
                || !Objects.equals(decision.selectedProviderModelId(), providerModelId)) {
            throw new IllegalStateException("Execution identity does not match persisted route decision");
        }
    }

    void requireSameBase(AiChatRequest request) {
        if (!organizationId.equals(request.organizationId())
                || !userId.equals(request.userId())
                || !chatId.equals(request.chatId())
                || !providerOperationId.equals(request.providerOperationId())
                || !Objects.equals(reservedInputUnits, request.reservedInputTokens())
                || !Objects.equals(maxOutputUnits, request.maxOutputTokens())
                || !baseRequestSha256.equals(baseSha256(request))) {
            throw new IllegalStateException("Reserved AI request identity was replaced");
        }
    }

    void requirePreparedBase(AiChatRequest reserved, AiChatRequest prepared) {
        if (!organizationId.equals(prepared.organizationId())
                || !userId.equals(prepared.userId())
                || !chatId.equals(prepared.chatId())
                || !providerOperationId.equals(prepared.providerOperationId())
                || !Objects.equals(reservedInputUnits, prepared.reservedInputTokens())
                || !Objects.equals(maxOutputUnits, prepared.maxOutputTokens())
                || !reserved.userMessage().equals(prepared.userMessage())
                || !reserved.history().equals(prepared.history())) {
            throw new IllegalStateException("Prepared request has a different immutable base identity");
        }
        if (knowledgeMode.usesKnowledge()) {
            requireAppendOnly(reserved.systemInstructions(), prepared.systemInstructions());
            requireAppendOnly(reserved.developerInstructions(), prepared.developerInstructions());
        } else if (!Objects.equals(reserved.systemInstructions(), prepared.systemInstructions())
                || !Objects.equals(reserved.developerInstructions(), prepared.developerInstructions())) {
            throw new IllegalStateException("Non-RAG route changed instructions");
        }
    }

    private static void requireAppendOnly(String base, String actual) {
        if (base == null) {
            return; // A RAG-enabled route may introduce SYSTEM/DEVELOPER instructions.
        }
        if (actual == null || !(actual.equals(base) || actual.startsWith(base + "\n\n"))) {
            throw new IllegalStateException("RAG replaced the original instruction prefix");
        }
    }

    static String baseSha256(AiChatRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                write(out, request.userMessage());
                write(out, request.systemInstructions());
                write(out, request.developerInstructions());
                out.writeInt(request.history().size());
                for (AiMessage message : request.history()) {
                    write(out, message.role().name());
                    write(out, message.content());
                }
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash base AI request", exception);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(encoded.length);
            out.write(encoded);
        }
    }
}
