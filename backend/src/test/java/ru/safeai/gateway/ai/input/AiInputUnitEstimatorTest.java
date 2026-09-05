package ru.safeai.gateway.ai.input;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiInputUnitEstimatorTest {

    @Test
    void instructionsIncreasePreparedInputUnits() {
        AiChatRequest base =
                new AiChatRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        "hello",
                        List.of(),
                        100_000L,
                        1024
                );

        AiChatRequest prepared =
                base.withInstructions(
                        "system",
                        "Русский RAG контекст"
                );

        assertThat(
                AiInputUnitEstimator
                        .estimatePreparedRequest(prepared)
        ).isGreaterThan(
                AiInputUnitEstimator
                        .estimatePreparedRequest(base)
        );
    }

    @Test
    void versionIsStableProvenanceIdentifier() {
        assertThat(AiInputUnitEstimator.VERSION)
                .isEqualTo(
                        "UTF8_STRUCTURAL_UNITS_V2"
                );
    }
}
