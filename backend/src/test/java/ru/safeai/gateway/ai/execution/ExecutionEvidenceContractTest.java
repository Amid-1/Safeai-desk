package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionEvidenceContractTest {

    @Test
    void completeEvidencePreservesCacheCountersOutsideChatMessageProjection() {
        AiChatResponse response = AiChatResponse.fromProvider(
                "answer", "gpt-5", "gpt-5", "message", "request",
                AiResponseStatus.COMPLETED, "stop",
                new AiTokenUsage(10_000, 8_000, 2_000, 500, true, true),
                PricingResult.unpriced(Instant.parse("2026-09-11T00:00:00Z"))
        );

        ProviderUsageEvidence evidence = ProviderUsageEvidence.from(response);

        assertThat(evidence.inputTokens()).isEqualTo(10_000);
        assertThat(evidence.cachedInputTokens()).isEqualTo(8_000);
        assertThat(evidence.cacheWriteInputTokens()).isEqualTo(2_000);
        assertThat(evidence.specializedDimensionsPresent()).isTrue();
    }

    @Test
    void unapprovedResolvedModelFailsClosed() {
        ProviderExecutionTarget target = ProviderExecutionTarget.staticTarget("openai", "gpt-5");

        assertThatThrownBy(() -> target.requireApprovedResolvedModel("gpt-5-2027-preview"))
                .isInstanceOf(ResolvedModelMismatchException.class);
    }
}
