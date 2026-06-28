package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatResponse;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatResponseTest {

    @Test
    void constructor_shouldApplyDefaultValuesForNullArguments() {
        AiChatResponse response = new AiChatResponse(
                null,
                null,
                null,
                null,
                null
        );

        assertThat(response.content()).isEmpty();
        assertThat(response.model()).isEqualTo("unknown");
        assertThat(response.inputTokens()).isZero();
        assertThat(response.outputTokens()).isZero();
        assertThat(response.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void constructor_shouldNormalizeNegativeTokenCountsAndNegativeCost() {
        AiChatResponse response = new AiChatResponse(
                "response",
                "gpt-4.1",
                -10,
                -20,
                BigDecimal.valueOf(-1.25)
        );

        assertThat(response.inputTokens()).isZero();
        assertThat(response.outputTokens()).isZero();
        assertThat(response.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void constructor_shouldPreserveValidValues() {
        AiChatResponse response = new AiChatResponse(
                "response",
                "gpt-4.1",
                100,
                50,
                new BigDecimal("0.1234")
        );

        assertThat(response.content()).isEqualTo("response");
        assertThat(response.model()).isEqualTo("gpt-4.1");
        assertThat(response.inputTokens()).isEqualTo(100);
        assertThat(response.outputTokens()).isEqualTo(50);
        assertThat(response.costUsd()).isEqualByComparingTo("0.1234");
    }
}