package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.ai.provider.openai.OpenAiProperties;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeModelHealthProbeAdaptersTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-03T12:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void mockProbeIsDeterministicAndContainsNoHttpEvidence() {
        MockRuntimeModelHealthProbe probe = new MockRuntimeModelHealthProbe(CLOCK);

        var result = probe.probe();

        assertThat(probe.provider()).isEqualTo("mock");
        assertThat(result.provider()).isEqualTo("mock");
        assertThat(result.model()).isEqualTo("mock-safeai");
        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.AVAILABLE);
        assertThat(result.checkedAt()).isEqualTo(CLOCK.instant());
        assertThat(result.latencyMs()).isZero();
        assertThat(result.httpStatus()).isNull();
    }

    @Test
    void openAiAdapterDelegatesThroughHardenedHttpsBoundaryWithoutLeakingApiKey() {
        OpenAiProperties properties = mock(OpenAiProperties.class);
        when(properties.model()).thenReturn("gpt-test");
        when(properties.baseUrl()).thenReturn("http://invalid-for-probe.example/v1");
        when(properties.apiKey()).thenReturn("openai-super-secret");
        when(properties.connectTimeout()).thenReturn(Duration.ofSeconds(1));
        when(properties.readTimeout()).thenReturn(Duration.ofSeconds(1));

        OpenAiRuntimeModelHealthProbe probe =
                new OpenAiRuntimeModelHealthProbe(properties, CLOCK);
        var result = probe.probe();

        assertThat(probe.provider()).isEqualTo("openai");
        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.ERROR);
        assertThat(result.message()).doesNotContain("openai-super-secret");
    }

    @Test
    void anthropicAdapterDelegatesThroughHardenedHttpsBoundaryWithoutLeakingApiKey() {
        AnthropicProperties properties = mock(AnthropicProperties.class);
        when(properties.model()).thenReturn("claude-test");
        when(properties.baseUrl()).thenReturn("http://invalid-for-probe.example/v1");
        when(properties.apiKey()).thenReturn("anthropic-super-secret");
        when(properties.version()).thenReturn("2023-06-01");
        when(properties.connectTimeout()).thenReturn(Duration.ofSeconds(1));
        when(properties.readTimeout()).thenReturn(Duration.ofSeconds(1));

        AnthropicRuntimeModelHealthProbe probe =
                new AnthropicRuntimeModelHealthProbe(properties, CLOCK);
        var result = probe.probe();

        assertThat(probe.provider()).isEqualTo("anthropic");
        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.ERROR);
        assertThat(result.message()).doesNotContain("anthropic-super-secret");
    }

    @Test
    void adapterConstructorsRejectNullConfigurationOrClock() {
        assertThatThrownBy(() -> new OpenAiRuntimeModelHealthProbe(null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AnthropicRuntimeModelHealthProbe(null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MockRuntimeModelHealthProbe(null))
                .isInstanceOf(NullPointerException.class);
    }
}
