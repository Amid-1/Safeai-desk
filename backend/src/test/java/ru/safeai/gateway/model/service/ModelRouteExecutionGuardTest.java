package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V53 exact sealed base and mandatory RAG envelope (not merely token caps). */
@Tag("unit")
class ModelRouteExecutionGuardTest {
    @Test
    void replacedTenantUserChatOrOperationIdentityIsRejectedBeforeProviderIo() {
        AiChatRequest base = request();
        ModelRouteExecutionIdentity identity = identity(base, KnowledgeMode.GENERAL, null);
        AiChatRequest foreign = new AiChatRequest(
                base.userId(), UUID.randomUUID(), base.chatId(),
                base.providerOperationId(), null, null, base.userMessage(),
                base.history(), base.reservedInputTokens(), base.maxOutputTokens());
        assertThatThrownBy(() -> ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity, base, foreign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable base identity");
        AiChatRequest changedOperation = new AiChatRequest(
                base.userId(), base.organizationId(), base.chatId(),
                UUID.randomUUID(), null, null, base.userMessage(),
                base.history(), base.reservedInputTokens(), base.maxOutputTokens());
        assertThatThrownBy(() -> ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity, base, changedOperation))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replacedUserMessageAndHistoryCannotMasqueradeAsAllowedRagContext() {
        AiChatRequest base = request();
        ModelRouteExecutionIdentity identity = identity(base, KnowledgeMode.GENERAL, null);
        AiChatRequest changed = new AiChatRequest(base.userId(), base.organizationId(),
                base.chatId(), base.providerOperationId(), null, null,
                "replacement", List.of(), 4096L, 512);
        assertThatThrownBy(() -> ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity, base, changed)).isInstanceOf(IllegalStateException.class);
        AiChatRequest changedHistory = changed.withHistory(List.of(
                new AiMessage(AiMessageRole.USER, "old"),
                new AiMessage(AiMessageRole.ASSISTANT, "answer")));
        assertThatThrownBy(() -> ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity, base, changedHistory)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generalModeForbidsNewInstructionsButKnowledgeModeAllowsAppendOnlyRag() {
        AiChatRequest base = request().withInstructions("mandatory", "developer");
        AiChatRequest rag = base.withInstructions("mandatory\n\nretrieved context", "developer");
        assertThatThrownBy(() -> ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity(base, KnowledgeMode.GENERAL, null), base, rag))
                .isInstanceOf(IllegalStateException.class);
        UUID kbId = UUID.randomUUID();
        assertThatCode(() -> ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity(base, KnowledgeMode.KNOWLEDGE_ASSISTED, kbId), base, rag))
                .doesNotThrowAnyException();
        AiChatRequest malicious = base.withInstructions("replaced mandatory", "developer");
        assertThatThrownBy(() -> ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity(base, KnowledgeMode.KNOWLEDGE_ASSISTED, kbId), base, malicious))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void overBudgetRagFailsBeforePhysicalExecution() {
        AiChatRequest base = request();
        long minimal = AiInputUnitEstimator.estimatePreparedRequest(base);
        AiChatRequest smallBudget = new AiChatRequest(base.userId(), base.organizationId(),
                base.chatId(), base.providerOperationId(), null, null,
                base.userMessage(), List.of(), minimal, 512);
        AiChatRequest oversized = smallBudget.withInstructions("extra RAG context", null);
        ModelRouteExecutionIdentity identity = identity(
                smallBudget, KnowledgeMode.KNOWLEDGE_ASSISTED, UUID.randomUUID());
        assertThatThrownBy(() -> ModelRouteExecutionGuard.assertWithinReservedInputEnvelope(
                identity, smallBudget, oversized))
                .isInstanceOf(ModelRouteEnvelopeExceededException.class)
                .satisfies(error -> {
                    ModelRouteEnvelopeExceededException exceeded =
                            (ModelRouteEnvelopeExceededException) error;
                    org.assertj.core.api.Assertions.assertThat(exceeded.decisionId())
                            .isEqualTo(identity.decisionId());
                    org.assertj.core.api.Assertions.assertThat(exceeded.reservedInputTokens())
                            .isEqualTo(minimal);
                    org.assertj.core.api.Assertions.assertThat(exceeded.actualEstimatedInputTokens())
                            .isGreaterThan(minimal);
                });
    }

    private static AiChatRequest request() {
        return new AiChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, null, "question", List.of(), 4_096L, 512);
    }

    private static ModelRouteExecutionIdentity identity(AiChatRequest base,
            KnowledgeMode mode, UUID knowledgeId) {
        return new ModelRouteExecutionIdentity(UUID.randomUUID(), UUID.randomUUID(), 1,
                "openai:gpt-5", base.organizationId(), base.userId(), base.chatId(),
                UUID.randomUUID(), UUID.randomUUID(), base.providerOperationId(),
                "a".repeat(64), ModelRouteExecutionIdentity.baseSha256(base),
                AiInputUnitEstimator.VERSION, base.reservedInputTokens(), base.maxOutputTokens(),
                "openai", "gpt-5", knowledgeId, mode);
    }
}
