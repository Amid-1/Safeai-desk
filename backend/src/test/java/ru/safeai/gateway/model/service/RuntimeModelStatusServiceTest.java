package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ru.safeai.gateway.ai.testsupport.CatalogPricingFixture;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.ai.provider.AiProviderProperties;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;
import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeModelStatusServiceTest {

    @Test
    void openAiAssuranceIsBoundToCredentialAndStoreMode() {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        OpenAiProperties openAi = mock(OpenAiProperties.class);
        when(openAi.baseUrl()).thenReturn("https://api.openai.com/v1");
        when(openAi.apiKey()).thenReturn("test-key");
        when(openAi.model()).thenReturn("gpt-test");
        when(openAi.maxInputTokens()).thenReturn(32000);
        when(openAi.maxOutputTokens()).thenReturn(2048);
        var configuration = new ru.safeai.gateway.ai.provider.ProviderDataAssuranceProperties(
                new ru.safeai.gateway.ai.provider.ProviderDataEvidence("CONTRACT-42", now,
                        now.plusSeconds(60), ru.safeai.gateway.ai.provider.ProviderDataEvidence.fingerprint(
                        "chat", "openai", "https://api.openai.com/v1", "gpt-test", "test-key", "false"),
                        true, true), null, null);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiProperties> openAiProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AnthropicProperties> anthropicProvider = mock(ObjectProvider.class);
        when(openAiProvider.getIfAvailable()).thenReturn(openAi);
        var service = new RuntimeModelStatusService(new AiProviderProperties("openai"),
                mock(ModelCatalogRepository.class), openAiProvider, anthropicProvider, clock, configuration);
        assertThat(service.current().dataAssurance().zeroDataRetention()).isTrue();
        assertThat(service.current().dataAssurance().noTraining()).isTrue();
        when(openAi.apiKey()).thenReturn("rotated-key");
        assertThat(service.current().dataAssurance().noTraining()).isFalse();
        when(openAi.apiKey()).thenReturn("test-key");
        when(openAi.effectiveStore()).thenReturn(true);
        assertThat(service.current().dataAssurance().zeroDataRetention()).isFalse();
        var storeConfiguration = new ru.safeai.gateway.ai.provider.ProviderDataAssuranceProperties(
                new ru.safeai.gateway.ai.provider.ProviderDataEvidence("CONTRACT-42", now,
                        now.plusSeconds(60), ru.safeai.gateway.ai.provider.ProviderDataEvidence.fingerprint(
                        "chat", "openai", "https://api.openai.com/v1", "gpt-test", "test-key", "true"),
                        true, true), null, null);
        var storeService = new RuntimeModelStatusService(new AiProviderProperties("openai"),
                mock(ModelCatalogRepository.class), openAiProvider, anthropicProvider, clock, storeConfiguration);
        assertThat(storeService.current().dataAssurance().noTraining()).isTrue();
        assertThat(storeService.current().dataAssurance().zeroDataRetention()).isFalse();
    }

    @Test
    void mockRuntimeIsTruthfulAndDoesNotPretendToHaveProviderCapabilities() {
        RuntimeModelStatusService service = service(
                "mock",
                List.of(),
                null,
                null
        );

        var status = service.currentWithCatalogPricing();

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

        var status = service.currentWithCatalogPricing();

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

        var status = service.currentWithCatalogPricing();

        assertThat(status.pricingStatus())
                .isEqualTo("CONFIGURED");
        assertThat(status.inputUsdPer1mTokens())
                .isEqualByComparingTo("1");
        assertThat(status.outputUsdPer1mTokens())
                .isEqualByComparingTo("4");
    }

    @Test
    void ambiguousRuntimeMappingDoesNotExposeAnotherLogicalModelsTariff() {
        var service = service("mock", List.of(
                price("mock-safeai", "1", "4", "v1"),
                price("mock-safeai", "0", "0", "v2")), null, null);
        var status = service.currentWithCatalogPricing();
        assertThat(status.pricingStatus()).isEqualTo("UNPRICED");
        assertThat(status.inputUsdPer1mTokens()).isNull();
        assertThat(status.outputUsdPer1mTokens()).isNull();
        assertThat(status.pricingVersion()).isNull();
    }

    @Test
    void runtimeNeverBorrowsAnAliasOrDifferentPhysicalModelsPrice() {
        var service = service("mock", List.of(
                price("different-model", "0", "0", "v-free")), null, null);
        assertThat(service.currentWithCatalogPricing().pricingStatus()).isEqualTo("UNPRICED");
    }

    @Test
    void dataPlaneRuntimeIdentityDoesNotLoadCatalogPricingTwice() {
        ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiProperties> openAi = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AnthropicProperties> anthropic = mock(ObjectProvider.class);
        var runtime = new RuntimeModelStatusService(
                new AiProviderProperties("mock"), catalog, openAi, anthropic);
        assertThat(runtime.current().model()).isEqualTo("mock-safeai");
        org.mockito.Mockito.verifyNoInteractions(catalog);
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

    @Test
    void freshMatchingProbeIsExposedOnlyWithinTheLocalTtl() {
        Instant now = Instant.parse("2026-10-03T12:00:00Z");
        RuntimeModelStatusService service = service(
                "mock",
                List.of(),
                null,
                null,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        service.recordProbe(new RuntimeModelProbeResult(
                "mock",
                "mock-safeai",
                RuntimeModelProbeStatus.AVAILABLE,
                now.minusSeconds(59),
                10L,
                200,
                "ok"
        ));

        assertThat(service.current().healthStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    void probeAtExactTtlBoundaryIsNotReportedAsCurrentHealth() {
        Instant now = Instant.parse("2026-10-03T12:00:00Z");
        RuntimeModelStatusService service = service(
                "mock", List.of(), null, null,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        service.recordProbe(new RuntimeModelProbeResult(
                "mock", "mock-safeai", RuntimeModelProbeStatus.AVAILABLE,
                now.minusSeconds(60), 10L, 200, "ok"
        ));

        assertThat(service.current().healthStatus()).isEqualTo("NOT_PROBED");
    }

    @Test
    void futureOrDifferentRuntimeProbeIsNeverReused() {
        Instant now = Instant.parse("2026-10-03T12:00:00Z");
        RuntimeModelStatusService service = service(
                "mock", List.of(), null, null,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        service.recordProbe(new RuntimeModelProbeResult(
                "mock", "another-model", RuntimeModelProbeStatus.AVAILABLE,
                now, 10L, 200, "ok"
        ));
        assertThat(service.current().healthStatus()).isEqualTo("NOT_PROBED");

        service.recordProbe(new RuntimeModelProbeResult(
                "mock", "mock-safeai", RuntimeModelProbeStatus.AVAILABLE,
                now.plusSeconds(1), 10L, 200, "ok"
        ));
        assertThat(service.current().healthStatus()).isEqualTo("NOT_PROBED");
    }

    @Test
    void olderObservationCannotOverwriteANewerProbe() {
        Instant now = Instant.parse("2026-10-03T12:00:00Z");
        RuntimeModelStatusService service = service(
                "mock", List.of(), null, null,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        service.recordProbe(new RuntimeModelProbeResult(
                "mock", "mock-safeai", RuntimeModelProbeStatus.RATE_LIMITED,
                now.minusSeconds(1), 10L, 429, "limited"
        ));
        service.recordProbe(new RuntimeModelProbeResult(
                "mock", "mock-safeai", RuntimeModelProbeStatus.AVAILABLE,
                now.minusSeconds(10), 10L, 200, "older"
        ));

        assertThat(service.current().healthStatus()).isEqualTo("RATE_LIMITED");
    }

    private static RuntimeModelStatusService service(
            String provider,
            List<CatalogPricingFixture.Price> prices,
            OpenAiProperties openAi,
            AnthropicProperties anthropic
    ) {
        return service(provider, prices, openAi, anthropic, Clock.systemUTC());
    }

    private static RuntimeModelStatusService service(
            String provider,
            List<CatalogPricingFixture.Price> prices,
            OpenAiProperties openAi,
            AnthropicProperties anthropic,
            Clock clock
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiProperties> openAiProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AnthropicProperties> anthropicProvider = mock(ObjectProvider.class);

        when(openAiProvider.getIfAvailable()).thenReturn(openAi);
        when(anthropicProvider.getIfAvailable()).thenReturn(anthropic);

        ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
        for (CatalogPricingFixture.Price price : prices) {
            when(catalog.findEffectiveByRuntime(org.mockito.ArgumentMatchers.eq(provider),
                    org.mockito.ArgumentMatchers.eq(price.model()), org.mockito.ArgumentMatchers.any(Instant.class)))
                    .thenReturn(prices.stream().filter(p -> p.model().equals(price.model()))
                            .map(p -> CatalogPricingFixture.entry(provider, p, clock)).toList());
        }
        return new RuntimeModelStatusService(
                new AiProviderProperties(provider),
                catalog,
                openAiProvider,
                anthropicProvider,
                clock
        );
    }

    private static CatalogPricingFixture.Price price(
            String model,
            String input,
            String output,
            String version
    ) {
        return new CatalogPricingFixture.Price(
                model,
                new BigDecimal(input),
                new BigDecimal(output),
                "USD",
                version
        );
    }
}
