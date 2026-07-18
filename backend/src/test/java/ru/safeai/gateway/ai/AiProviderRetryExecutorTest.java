package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiRetryProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderRetryExecutorTest {

    @Test
    void retriesBoundedRateLimitWithRetryAfter() {
        AiProviderRetryExecutor executor = executor(2);
        AtomicInteger attempts = new AtomicInteger();

        AiChatResponse result = executor.execute(
                "openai",
                "gpt-4.1",
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new AiProviderRateLimitedException(
                                "openai",
                                "gpt-4.1",
                                429,
                                "req-1",
                                Duration.ofMillis(10),
                                true,
                                "rate limited",
                                null
                        );
                    }

                    return response();
                }
        );

        assertThat(result.content()).isEqualTo("Ответ");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRetryAmbiguousReadTimeout() {
        AiProviderRetryExecutor executor = executor(3);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(
                "openai",
                "gpt-4.1",
                () -> {
                    attempts.incrementAndGet();
                    throw new AiProviderTimeoutException(
                            "openai",
                            "gpt-4.1",
                            true,
                            "read timeout",
                            null
                    );
                }
        )).isInstanceOf(AiProviderTimeoutException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void maxAttemptsOneNeverRetries() {
        AiProviderRetryExecutor executor = executor(1);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(
                "openai",
                "gpt-4.1",
                () -> {
                    attempts.incrementAndGet();
                    throw new AiProviderRateLimitedException(
                            "openai",
                            "gpt-4.1",
                            429,
                            "req-1",
                            Duration.ofMillis(10),
                            true,
                            "rate limited",
                            null
                    );
                }
        )).isInstanceOf(AiProviderRateLimitedException.class);

        assertThat(attempts).hasValue(1);
    }

    private AiProviderRetryExecutor executor(int attempts) {
        return new AiProviderRetryExecutor(
                new AiRetryProperties(
                        true,
                        attempts,
                        Duration.ofMillis(10),
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1)
                )
        );
    }

    private AiChatResponse response() {
        return new AiChatResponse(
                "Ответ",
                "gpt-4.1",
                "resp-1",
                AiResponseStatus.COMPLETED,
                "completed",
                1,
                1,
                UsageStatus.AVAILABLE,
                BigDecimal.ZERO,
                PricingStatus.FREE,
                "USD",
                "test-v1",
                Instant.parse("2026-07-12T12:00:00Z")
        );
    }
}
