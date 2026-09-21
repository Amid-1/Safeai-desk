package ru.safeai.gateway.ai.input;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AiInputUnitEstimatorTest {
    @Test
    void utf8StructuralUnitsIncludeUnicodeFramingHistoryAndBothInstructions() {
        AiChatRequest base = request("hello", List.of());
        AiChatRequest unicode = request("Привет", List.of());
        AiChatRequest rag = unicode.withInstructions("system", "Русский RAG контекст");
        AiChatRequest history = rag.withHistory(List.of(
                new AiMessage(AiMessageRole.USER, "older"),
                new AiMessage(AiMessageRole.ASSISTANT, "answer")));
        assertThat(AiInputUnitEstimator.estimatePreparedRequest(base))
                .isEqualTo(5L + AiInputUnitEstimator.MESSAGE_OVERHEAD_UNITS);
        assertThat(AiInputUnitEstimator.estimatePreparedRequest(unicode))
                .isGreaterThan(AiInputUnitEstimator.estimatePreparedRequest(base));
        assertThat(AiInputUnitEstimator.estimatePreparedRequest(rag))
                .isGreaterThan(AiInputUnitEstimator.estimatePreparedRequest(unicode));
        assertThat(AiInputUnitEstimator.estimatePreparedRequest(history))
                .isGreaterThan(AiInputUnitEstimator.estimatePreparedRequest(rag));
    }

    @Test
    void canonicalAccountVersionMustNotDriftWithoutMigration() {
        assertThat(AiInputUnitEstimator.VERSION).isEqualTo("UTF8_STRUCTURAL_UNITS_V2");
        assertThat(AiInputUnitEstimator.utf8Length("🙂")).isEqualTo(4);
        assertThatThrownBy(() -> AiInputUnitEstimator.estimatePreparedRequest(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static AiChatRequest request(String text, List<AiMessage> history) {
        return new AiChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, null, text, history, 100_000L, 1024);
    }
}
