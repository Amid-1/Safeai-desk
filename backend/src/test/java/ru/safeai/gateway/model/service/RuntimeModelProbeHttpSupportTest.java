package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeModelProbeHttpSupportTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void nonHttpsBaseUrlFailsClosedWithoutLeakingUrlOrCredentials() {
        RuntimeModelProbeResult result = RuntimeModelProbeHttpSupport.probeModel(
                "openai",
                "gpt-test",
                "http://provider.internal/v1",
                Map.of("Authorization", "Bearer super-secret"),
                Duration.ofMillis(1),
                Duration.ofSeconds(30),
                CLOCK
        );

        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.ERROR);
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.message())
                .isEqualTo("Проверка завершилась внутренней ошибкой")
                .doesNotContain("super-secret", "provider.internal");
    }

    @Test
    void userInfoQueryAndFragmentAreRejectedBeforeNetworkIo() {
        for (String baseUrl : new String[]{
                "https://user:password@example.com/v1",
                "https://example.com/v1?token=secret",
                "https://example.com/v1#fragment"
        }) {
            RuntimeModelProbeResult result = RuntimeModelProbeHttpSupport.probeModel(
                    "openai", "gpt-test", baseUrl,
                    Map.of("Authorization", "Bearer hidden"),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), CLOCK
            );
            assertThat(result.status()).as(baseUrl).isEqualTo(RuntimeModelProbeStatus.ERROR);
            assertThat(result.message()).doesNotContain("hidden", "password", "secret");
        }
    }

    @Test
    void blankHeaderIsConvertedToSanitizedInternalError() {
        RuntimeModelProbeResult result = RuntimeModelProbeHttpSupport.probeModel(
                "anthropic",
                "claude-test",
                "https://example.com/v1",
                Map.of("x-api-key", " "),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                CLOCK
        );

        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.ERROR);
        assertThat(result.message()).isEqualTo("Проверка завершилась внутренней ошибкой");
    }

    @Test
    void nullHeadersAndClockAreProgrammerErrorsRatherThanProviderHealthStates() {
        assertThatThrownBy(() -> RuntimeModelProbeHttpSupport.probeModel(
                "openai", "gpt-test", "https://example.com/v1", null,
                Duration.ofSeconds(1), Duration.ofSeconds(1), CLOCK
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("headers");

        assertThatThrownBy(() -> RuntimeModelProbeHttpSupport.probeModel(
                "openai", "gpt-test", "https://example.com/v1", Map.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), null
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");
    }
}
