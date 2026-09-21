package ru.safeai.gateway.ai.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.metadata.PricingStatus;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class GovernedAiTestFixturesTest {
    @Test
    void governedFixturePreservesOriginalLogicalIdentityWhileAddingCaps() {
        var request = GovernedAiTestFixtures.requestWithCaps(4_096L, 128);
        assertThat(request.userId()).isEqualTo(AiTestFixtures.USER_ID);
        assertThat(request.organizationId()).isEqualTo(AiTestFixtures.ORGANIZATION_ID);
        assertThat(request.chatId()).isEqualTo(AiTestFixtures.CHAT_ID);
        assertThat(request.providerOperationId()).isEqualTo(AiTestFixtures.OPERATION_ID);
        assertThat(request.reservedInputTokens()).isEqualTo(4_096L);
        assertThat(request.effectiveMaxOutputTokens(1_024)).isEqualTo(128);
    }

    @Test
    void exactFreeFixtureCannotAccidentallyTriggerResolvedModelMismatch() {
        var response = GovernedAiTestFixtures.exactFreeResponse("gpt-test");
        assertThat(response.requestedModel()).isEqualTo("gpt-test");
        assertThat(response.model()).isEqualTo("gpt-test");
        assertThat(response.pricingStatus()).isEqualTo(PricingStatus.FREE);
    }

    @Test
    void specializedFixtureExposesAllUsageEvenWhenCostCannotBeProven() {
        var response = GovernedAiTestFixtures.specializedUnpricedResponse("gpt-test");
        assertThat(response.cachedInputTokens()).isEqualTo(800);
        assertThat(response.cacheWriteInputTokens()).isEqualTo(200);
        assertThat(response.specializedBillingDimensionsPresent()).isTrue();
        assertThat(response.specializedBillingDimensionsValid()).isTrue();
        assertThat(response.pricingStatus()).isEqualTo(PricingStatus.UNPRICED);
    }
}
