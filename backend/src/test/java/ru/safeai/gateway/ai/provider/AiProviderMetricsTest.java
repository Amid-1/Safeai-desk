package ru.safeai.gateway.ai.provider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.execution.AiExecutionAttemptObserver;
import ru.safeai.gateway.ai.execution.AiExecutionAttemptScope;
import ru.safeai.gateway.ai.execution.PhysicalAttemptReconciliationRequiredException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

@Tag("unit")
class AiProviderMetricsTest {
    @Test
    void realExecutorDoesNotCountRejectedDispatchAsAdapterEntry() {
        var registry = new SimpleMeterRegistry();
        try {
            var scope = new AiExecutionAttemptScope();
            var observer = mock(AiExecutionAttemptObserver.class);
            var denial = new IllegalStateException("dispatch fence denied");
            doThrow(denial).when(observer).beforeNetworkDispatch(any());
            var executor = new AiProviderRetryExecutor(
                    new AiRetryProperties(false, 1, null, null, null, null),
                    scope, Optional.of(new AiProviderMetrics(registry)));
            UUID operation = UUID.randomUUID();
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> scope.execute(operation, observer, () ->
                    executor.execute("openai", "private-model", operation, Duration.ZERO, ignored -> {
                        calls.incrementAndGet();
                        return mock(AiChatResponse.class);
                    }))).isInstanceOf(PhysicalAttemptReconciliationRequiredException.class)
                    .hasCause(denial);
            assertThat(calls.get()).isZero();
            assertThat(registry.get("safeai.ai.provider.gate.failures").tag("provider", "openai")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.ai.provider.duration").tag("provider", "openai")
                    .timer().count()).isZero();
        } finally {
            registry.close();
        }
    }

    @Test
    void preservesResultsAndExceptionsAndBoundsCardinality() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new AiProviderMetrics(registry);
            var response = mock(AiChatResponse.class);
            var rejection = new AiProviderException("openai", "secret-model", 429, null,
                    AiProviderErrorType.RATE_LIMITED, true, false, null, "private response", null);
            assertThat(metrics.observe("openai", 1, () -> response)).isSameAs(response);
            assertThatThrownBy(() -> metrics.observe("openai", 2, () -> {
                throw rejection;
            })).isSameAs(rejection);
            var unexpected = new IllegalStateException("private details");
            assertThatThrownBy(() -> metrics.observe("untrusted-secret", 1, () -> {
                throw unexpected;
            })).isSameAs(unexpected);
            metrics.gateFailure("openai");
            metrics.recordingFailure("openai");
            assertThat(registry.get("safeai.ai.provider.attempts")
                    .tags("provider", "openai", "result", "success").counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.ai.provider.attempts")
                    .tags("provider", "openai", "result", "rate_limited").counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.ai.provider.attempts")
                    .tags("provider", "other", "result", "unexpected").counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.ai.provider.retries").tag("provider", "openai")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.ai.provider.duration").tag("provider", "openai")
                    .timer().count()).isEqualTo(2);
            assertThat(registry.get("safeai.ai.provider.gate.failures").tag("provider", "openai")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("safeai.ai.provider.recording.failures").tag("provider", "openai")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                        assertThat(tag.getKey()).isIn("provider", "result");
                        assertThat(tag.getValue()).doesNotContain("secret", "private");
                    }));
        } finally {
            registry.close();
        }
    }
}
