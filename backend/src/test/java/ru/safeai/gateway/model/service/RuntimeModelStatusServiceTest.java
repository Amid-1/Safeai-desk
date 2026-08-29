package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ru.safeai.gateway.ai.pricing.ModelPricingProperties;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeModelStatusServiceTest {

    @Test
    void mockRuntimeIsTruthfulAndDoesNotPretendToHaveProviderCapabilities() {
        RuntimeModelStatusService service = service(
                "mock",
                List.of(),
                null,
                null
        );

        var status = service.current();

        assertThat(status.provider())
                .isEqualTo("mock");
        assertThat(status.model())
                .isEqualTo("mock-safeai");
        assertThat(status.enabled())
                .isTrue();
        assertThat(status.routingMode())
                .isEqualTo("SINGLE_PROVIDER_STATIC");
        assertThat(status.healthStatus())
                .isEqualTo("NOT_PROBED");
        assertThat(status.dataRetentionStatus())
                .isEqualTo("NOT_DECLARED");
        assertThat(status.toolsSupported())
                .isFalse();
        assertThat(status.visionSupported())
                .isFalse();
        assertThat(status.structuredOutputSupported())
                .isFalse();
        assertThat(status.pricingStatus())
                .isEqualTo("UNPRICED");
    }

    @Test
    void freePricingSnapshotIsReportedAsFree() {
        RuntimeModelStatusService service = service(
                "mock",
                List.of(price(
                        "mock-safeai",
                        "0",
                        "0",
                        "v-free"
                )),
                null,
                null
        );

        var status = service.current();

        assertThat(status.pricingStatus())
                .isEqualTo("FREE");
        assertThat(status.inputUsdPer1mTokens())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(status.outputUsdPer1mTokens())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(status.pricingVersion())
                .isEqualTo("v-free");
    }

    @Test
    void configuredFlatPricingIsReportedWithoutClaimingMoreMetadata() {
        RuntimeModelStatusService service = service(
                "mock",
                List.of(price(
                        "mock-safeai",
                        "1",
                        "4",
                        "v1"
                )),
                null,
                null
        );

        var status = service.current();

        assertThat(status.pricingStatus())
                .isEqualTo("CONFIGURED");
        assertThat(status.inputUsdPer1mTokens())
                .isEqualByComparingTo("1");
        assertThat(status.outputUsdPer1mTokens())
                .isEqualByComparingTo("4");
    }

    @Test
    void openAiRuntimeUsesOnlyConfiguredModelAndTokenLimits() {
        OpenAiProperties openAi = mock(OpenAiProperties.class);
        when(openAi.model()).thenReturn("gpt-test");
        when(openAi.maxInputTokens()).thenReturn(128_000);
        when(openAi.maxOutputTokens()).thenReturn(8_192);

        RuntimeModelStatusService service = service(
                "openai",
                List.of(price("gpt-test", "2", "8", "v1")),
                openAi,
                null
        );

        var status = service.current();

        assertThat(status.provider())
                .isEqualTo("openai");
        assertThat(status.model())
                .isEqualTo("gpt-test");
        assertThat(status.maxInputTokens())
                .isEqualTo(128_000);
        assertThat(status.maxOutputTokens())
                .isEqualTo(8_192);
    }

    @Test
    void anthropicRuntimeUsesConfiguredMaxTokens() {
        AnthropicProperties anthropic = mock(AnthropicProperties.class);
        when(anthropic.model()).thenReturn("claude-test");
        when(anthropic.maxInputTokens()).thenReturn(200_000);
        when(anthropic.maxTokens()).thenReturn(4_096);

        RuntimeModelStatusService service = service(
                "anthropic",
                List.of(),
                null,
                anthropic
        );

        var status = service.current();

        assertThat(status.provider())
                .isEqualTo("anthropic");
        assertThat(status.model())
                .isEqualTo("claude-test");
        assertThat(status.maxInputTokens())
                .isEqualTo(200_000);
        assertThat(status.maxOutputTokens())
                .isEqualTo(4_096);
    }

    @Test
    void blankConfiguredRuntimeModelFailsClosed() {
        OpenAiProperties openAi = mock(OpenAiProperties.class);
        when(openAi.model()).thenReturn("   ");
        when(openAi.maxInputTokens()).thenReturn(128_000);
        when(openAi.maxOutputTokens()).thenReturn(8_192);

        RuntimeModelStatusService service = service(
                "openai",
                List.of(),
                openAi,
                null
        );

        assertThatThrownBy(service::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtime model");
    }

    @Test
    void nonPositiveRuntimeLimitsFailClosed() {
        OpenAiProperties openAi = mock(OpenAiProperties.class);
        when(openAi.model()).thenReturn("gpt-test");
        when(openAi.maxInputTokens()).thenReturn(0);
        when(openAi.maxOutputTokens()).thenReturn(8_192);

        RuntimeModelStatusService service = service(
                "openai",
                List.of(),
                openAi,
                null
        );

        assertThatThrownBy(service::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token limits");
    }

    @Test
    void missingOpenAiPropertiesFailsFastInsteadOfReturningPartialStatus() {
        RuntimeModelStatusService service = service(
                "openai",
                List.of(),
                null,
                null
        );

        assertThatThrownBy(service::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAI provider properties");
    }

    @Test
    void missingAnthropicPropertiesFailsFastInsteadOfReturningPartialStatus() {
        RuntimeModelStatusService service = service(
                "anthropic",
                List.of(),
                null,
                null
        );

        assertThatThrownBy(service::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Anthropic provider properties");
    }

    private static RuntimeModelStatusService service(
            String provider,
            List<ModelPricingProperties.ModelPrice> prices,
            OpenAiProperties openAi,
            AnthropicProperties anthropic
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiProperties> openAiProvider =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AnthropicProperties> anthropicProvider =
                mock(ObjectProvider.class);

        when(openAiProvider.getIfAvailable())
                .thenReturn(openAi);
        when(anthropicProvider.getIfAvailable())
                .thenReturn(anthropic);

        return new RuntimeModelStatusService(
                new AiProviderProperties(provider),
                new ModelPricingProperties(prices),
                openAiProvider,
                anthropicProvider
        );
    }

    private static ModelPricingProperties.ModelPrice price(
            String model,
            String input,
            String output,
            String version
    ) {
        return new ModelPricingProperties.ModelPrice(
                model,
                new BigDecimal(input),
                new BigDecimal(output),
                "USD",
                version
        );
    }
}
