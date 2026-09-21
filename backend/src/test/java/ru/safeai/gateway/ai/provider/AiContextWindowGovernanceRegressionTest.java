package ru.safeai.gateway.ai.provider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.exception.AiContextLimitException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the physical output reservation and the immutable routing envelope together. */
@Tag("unit")
class AiContextWindowGovernanceRegressionTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID CHAT = UUID.randomUUID();
    private static final UUID OPERATION = UUID.randomUUID();

    @Test
    void noTruncationPreservesAllIdentityCapsAndInstructions() {
        AiChatRequest original = request(List.of(pairUser("old"), pairAssistant("answer")));
        AiChatRequest prepared = service(4).prepare(original, 100, 10);
        assertIdentityAndCaps(prepared);
        assertThat(prepared.history()).isEqualTo(original.history());
        assertThat(prepared.systemInstructions()).isEqualTo("s");
        assertThat(prepared.developerInstructions()).isEqualTo("d");
        assertThat(prepared.userMessage()).isEqualTo("new");
    }

    @Test
    void tokenBudgetKeepsOnlyNewestCompletePairWithoutChangingRouteCaps() {
        AiChatRequest original = request(List.of(
                pairUser("u1"), pairAssistant("a1"),
                pairUser("u2"), pairAssistant("a2")));
        // 3 mandatory + 1 system + 1 developer = 5, newest pair = 4;
        // with maxInput=10 and outputReservation=1 budget is exactly 9.
        AiChatRequest prepared = service(4).prepare(original, 10, 1);
        assertThat(prepared.history()).extracting(AiMessage::content)
                .containsExactly("u2", "a2");
        assertIdentityAndCaps(prepared);
    }

    @Test
    void oneLessTokenThanCompletePairCannotLeaveOrphanAssistant() {
        AiChatRequest original = request(List.of(pairUser("u1"), pairAssistant("a1")));
        AiChatRequest prepared = service(4).prepare(original, 9, 1);
        assertThat(prepared.history()).isEmpty();
        assertIdentityAndCaps(prepared);
    }

    @Test
    void characterBudgetTruncationPreservesGovernanceMetadata() {
        java.util.ArrayList<AiMessage> history = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(pairUser("u".repeat(80)));
            history.add(pairAssistant("a".repeat(80)));
        }
        AiChatRequest original = request(history);
        AiChatRequest prepared = service(20).prepare(original, 100_000, 0);
        assertThat(prepared.history().size()).isLessThan(original.history().size());
        assertThat(prepared.history().size() % 2).isZero();
        assertIdentityAndCaps(prepared);
    }

    @Test
    void outputReservationAndMarginCanExhaustBudgetBeforeProviderIo() {
        AiChatRequest original = request(List.of());
        assertThatThrownBy(() -> service(4).prepare(original, 8, 8))
                .isInstanceOf(AiContextLimitException.class)
                .hasMessageContaining("budget");
        assertThatThrownBy(() -> service(4).prepare(original, 5, 5))
                .isInstanceOf(AiContextLimitException.class)
                .hasMessageContaining("budget");
    }

    @Test
    void mandatoryPromptCannotBeTruncatedAwayToForceAProviderCall() {
        AiChatRequest original = request(List.of());
        assertThatThrownBy(() -> service(4).prepare(original, 4, 0))
                .isInstanceOf(AiContextLimitException.class)
                .hasMessageContaining("Обязательные");
    }

    @Test
    void callerCannotPassIllegalContextBudget() {
        AiChatRequest original = request(List.of());
        assertThatThrownBy(() -> service(4).prepare(original, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(4).prepare(original, 100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiContextWindowService service(int maxHistoryMessages) {
        return new AiContextWindowService(new AiContextWindowProperties(
                100, 100, maxHistoryMessages, 100, 1_000, 1, 0, 0));
    }

    private static AiChatRequest request(List<AiMessage> history) {
        return new AiChatRequest(USER, ORGANIZATION, CHAT, OPERATION,
                "s", "d", "new", history, 4_096L, 512);
    }

    private static void assertIdentityAndCaps(AiChatRequest prepared) {
        assertThat(prepared.userId()).isEqualTo(USER);
        assertThat(prepared.organizationId()).isEqualTo(ORGANIZATION);
        assertThat(prepared.chatId()).isEqualTo(CHAT);
        assertThat(prepared.providerOperationId()).isEqualTo(OPERATION);
        assertThat(prepared.reservedInputTokens()).isEqualTo(4_096L);
        assertThat(prepared.maxOutputTokens()).isEqualTo(512);
        assertThat(prepared.effectiveMaxOutputTokens(2_048)).isEqualTo(512);
        assertThat(prepared.effectiveMaxOutputTokens(128)).isEqualTo(128);
    }

    private static AiMessage pairUser(String text) {
        return new AiMessage(AiMessageRole.USER, text);
    }

    private static AiMessage pairAssistant(String text) {
        return new AiMessage(AiMessageRole.ASSISTANT, text);
    }
}
