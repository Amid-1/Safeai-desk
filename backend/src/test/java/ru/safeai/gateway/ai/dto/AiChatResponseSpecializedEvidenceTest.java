package ru.safeai.gateway.ai.dto;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Complements the existing flat AiChatResponseTest without replacing compatibility assertions. */
@Tag("unit")
class AiChatResponseSpecializedEvidenceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    @Test
    void completeFactoryPreservesEveryBillingDimensionAndProviderIdentity() {
        AiChatResponse response = response(new AiTokenUsage(
                20_000, 15_000, 2_000, 500, true, true));
        assertThat(response.requestedModel()).isEqualTo("gpt-requested");
        assertThat(response.model()).isEqualTo("gpt-resolved");
        assertThat(response.providerMessageId()).isEqualTo("resp-test");
        assertThat(response.providerRequestId()).isEqualTo("req-test");
        assertThat(response.inputTokens()).isEqualTo(20_000);
        assertThat(response.cachedInputTokens()).isEqualTo(15_000);
        assertThat(response.cacheWriteInputTokens()).isEqualTo(2_000);
        assertThat(response.outputTokens()).isEqualTo(500);
        assertThat(response.specializedBillingDimensionsPresent()).isTrue();
        assertThat(response.specializedBillingDimensionsValid()).isTrue();
        assertThat(response.usageStatus()).isEqualTo(UsageStatus.AVAILABLE);
        assertThat(response.pricingStatus()).isEqualTo(PricingStatus.UNPRICED);
        assertThat(response.costUsd()).isNull();
        assertThat(response.pricingCalculatedAt()).isEqualTo(NOW);
    }

    @Test
    void validZeroSpecializedCountersArePresentButNotBillable() {
        AiChatResponse response = response(new AiTokenUsage(10, 0, 0, 2, true, true));
        assertThat(response.cachedInputTokens()).isZero();
        assertThat(response.cacheWriteInputTokens()).isZero();
        assertThat(response.specializedBillingDimensionsPresent()).isTrue();
        assertThat(response.specializedBillingDimensionsValid()).isTrue();
    }

    @Test
    void malformedSpecializedEvidenceRemainsObservableRatherThanVanishing() {
        AiChatResponse response = response(new AiTokenUsage(10, null, null, 2, true, false));
        assertThat(response.specializedBillingDimensionsPresent()).isTrue();
        assertThat(response.specializedBillingDimensionsValid()).isFalse();
        assertThat(response.cachedInputTokens()).isNull();
        assertThat(response.cacheWriteInputTokens()).isNull();
    }

    @Test
    void absentSpecializedEvidenceIsDistinguishedFromPresentZeroCounters() {
        AiChatResponse response = response(AiTokenUsage.basic(10, 2));
        assertThat(response.specializedBillingDimensionsPresent()).isFalse();
        assertThat(response.specializedBillingDimensionsValid()).isTrue();
        assertThat(response.cachedInputTokens()).isNull();
        assertThat(response.cacheWriteInputTokens()).isNull();
    }

    @Test
    void oldFlatCompatibilityConstructorIntentionallyCannotInventCacheEvidence() {
        AiChatResponse response = AiChatResponse.fromProvider(
                "answer", "gpt-requested", "gpt-resolved", "msg", "req",
                AiResponseStatus.COMPLETED, "completed", 10, 2,
                PricingResult.unpriced(NOW));
        assertThat(response.specializedBillingDimensionsPresent()).isFalse();
        assertThat(response.specializedBillingDimensionsValid()).isTrue();
        assertThat(response.cachedInputTokens()).isNull();
        assertThat(response.cacheWriteInputTokens()).isNull();
    }

    @Test
    void missingSpecializedPresenceCannotCarryNonNullCacheCounter() {
        assertThatThrownBy(() -> constructForValidation(3, null, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Absent specialized");
        assertThatThrownBy(() -> constructForValidation(null, 3, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Absent specialized");
    }

    @Test
    void missingSpecializedPresenceMustBeMarkedValid() {
        assertThatThrownBy(() -> constructForValidation(null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Absent specialized");
    }

    @Test
    void negativeSpecializedCountersAreRejectedEvenWhenEvidenceIsPresent() {
        assertThatThrownBy(() -> constructForValidation(-1, null, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cachedInputTokens");
        assertThatThrownBy(() -> constructForValidation(null, -1, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheWriteInputTokens");
    }

    @Test
    void providerResponseCannotClaimCalculatedPricingWithoutCompleteUsage() {
        PricingResult priced = new PricingResult(PricingStatus.PRICED,
                new BigDecimal("0.123000000000"), "USD", "cat-v1", NOW);
        assertThatThrownBy(() -> AiChatResponse.fromProvider(
                "answer", "requested", "resolved", "msg", "req",
                AiResponseStatus.COMPLETED, "completed",
                new AiTokenUsage(null, null, null, 1, false, true), priced))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVAILABLE");
    }

    private static AiChatResponse response(AiTokenUsage usage) {
        return AiChatResponse.fromProvider(
                "answer", "gpt-requested", "gpt-resolved", "resp-test", "req-test",
                AiResponseStatus.COMPLETED, "completed", usage, PricingResult.unpriced(NOW));
    }

    private static void constructForValidation(Integer cached, Integer write,
                                               boolean present, boolean valid) {
        new AiChatResponse("answer", "gpt-requested", "gpt-resolved",
                "resp-test", "req-test", AiResponseStatus.COMPLETED, "completed",
                10, cached, write, present, valid, 2, UsageStatus.AVAILABLE,
                null, PricingStatus.UNPRICED, null, null, NOW);
    }
}
