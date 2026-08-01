package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderQuotaExceededException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiRetryProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.OPERATION_ID;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.freeResponse;

@Tag("unit")
@Timeout(5)
class AiProviderRetryExecutorTest {

    private static final String PROVIDER =
            "openai";

    private static final String MODEL =
            "gpt-4.1";

    private static final Duration RETRY_BACKOFF =
            Duration.ofMillis(10);

    private static final Duration MAX_RETRY_AFTER =
            Duration.ofSeconds(1);

    private static final Duration TOTAL_TIMEOUT =
            Duration.ofSeconds(2);

    @Test
    void connectFailureCanBeRetried() {
        AiProviderRetryExecutor executor =
                retryExecutor(2);

        AtomicInteger attempts =
                new AtomicInteger();

        AiChatResponse result =
                executor.execute(
                        PROVIDER,
                        MODEL,
                        OPERATION_ID,
                        Duration.ZERO,
                        context -> {
                            int currentAttempt =
                                    attempts.incrementAndGet();

                            if (currentAttempt == 1) {
                                throw new AiProviderUnavailableException(
                                        PROVIDER,
                                        MODEL,
                                        true,
                                        false,
                                        "connect failure",
                                        null
                                );
                            }

                            return freeResponse();
                        }
                );

        assertThat(result.content())
                .isEqualTo("Ответ");

        assertThat(attempts)
                .hasValue(2);
    }

    @Test
    void ambiguousReadTimeoutIsNotRetried() {
        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(
                () -> retryExecutor(3).execute(
                        PROVIDER,
                        MODEL,
                        OPERATION_ID,
                        Duration.ZERO,
                        context -> {
                            attempts.incrementAndGet();

                            throw new AiProviderTimeoutException(
                                    PROVIDER,
                                    MODEL,
                                    true,
                                    "read timeout after submission",
                                    null
                            );
                        }
                )
        )
                .isInstanceOf(
                        AiProviderTimeoutException.class
                );

        assertThat(attempts)
                .hasValue(1);
    }

    @Test
    void ambiguousNetworkOutcomeIsNotRetried() {
        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(
                () -> retryExecutor(3).execute(
                        PROVIDER,
                        MODEL,
                        OPERATION_ID,
                        Duration.ZERO,
                        context -> {
                            attempts.incrementAndGet();

                            throw new AiProviderUnavailableException(
                                    PROVIDER,
                                    MODEL,
                                    false,
                                    true,
                                    "ambiguous network failure",
                                    null
                            );
                        }
                )
        )
                .isInstanceOf(
                        AiProviderUnavailableException.class
                );

        assertThat(attempts)
                .hasValue(1);
    }

    @Test
    void eachAttemptHasUniqueIdAndLogicalOperationIsStable() {
        List<AiProviderAttemptContext> contexts =
                new ArrayList<>();

        AiChatResponse result =
                retryExecutor(2).execute(
                        PROVIDER,
                        MODEL,
                        OPERATION_ID,
                        Duration.ZERO,
                        context -> {
                            contexts.add(context);

                            if (context.attemptNumber() == 1) {
                                throw rateLimit();
                            }

                            return freeResponse();
                        }
                );

        assertThat(result.content())
                .isEqualTo("Ответ");

        assertThat(contexts)
                .hasSize(2);

        assertThat(contexts)
                .extracting(
                        AiProviderAttemptContext::operationId
                )
                .containsOnly(OPERATION_ID);

        assertThat(contexts)
                .extracting(
                        AiProviderAttemptContext::attemptNumber
                )
                .containsExactly(1, 2);

        assertThat(contexts)
                .extracting(
                        AiProviderAttemptContext::attemptId
                )
                .doesNotHaveDuplicates();

        assertThat(contexts.get(0).attemptId())
                .isNotEqualTo(
                        contexts.get(1).attemptId()
                );
    }

    @Test
    void exhaustedQuotaIsNeverRetried() {
        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(
                () -> retryExecutor(3).execute(
                        PROVIDER,
                        MODEL,
                        OPERATION_ID,
                        Duration.ZERO,
                        context -> {
                            attempts.incrementAndGet();

                            throw new AiProviderQuotaExceededException(
                                    PROVIDER,
                                    MODEL,
                                    429,
                                    "request-id",
                                    "insufficient_quota",
                                    "quota exhausted",
                                    null
                            );
                        }
                )
        )
                .isInstanceOf(
                        AiProviderQuotaExceededException.class
                );

        assertThat(attempts)
                .hasValue(1);
    }

    @Test
    void retryDoesNotStartAttemptThatCannotFitTotalDeadline() {
        AiProviderRetryExecutor executor =
                new AiProviderRetryExecutor(
                        new AiRetryProperties(
                                true,
                                3,
                                RETRY_BACKOFF,
                                RETRY_BACKOFF,
                                MAX_RETRY_AFTER,
                                Duration.ofSeconds(1)
                        )
                );

        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(
                () -> executor.execute(
                        PROVIDER,
                        MODEL,
                        OPERATION_ID,
                        Duration.ofSeconds(2),
                        context -> {
                            attempts.incrementAndGet();
                            throw rateLimit();
                        }
                )
        )
                .isInstanceOf(
                        AiProviderRateLimitedException.class
                );

        assertThat(attempts)
                .hasValue(1);
    }

    @Test
    void interruptedRetryRestoresInterruptFlag() {
        Thread currentThread =
                Thread.currentThread();

        currentThread.interrupt();

        try {
            assertThatThrownBy(
                    () -> retryExecutor(2).execute(
                            PROVIDER,
                            MODEL,
                            OPERATION_ID,
                            Duration.ZERO,
                            context -> {
                                throw rateLimitWithDelay();
                            }
                    )
            )
                    .isInstanceOf(AiProviderException.class)
                    .hasMessageContaining("interrupted");

            assertThat(currentThread.isInterrupted())
                    .isTrue();
        } finally {
            boolean wasInterrupted =
                    Thread.interrupted();

            assertThat(wasInterrupted)
                    .as("Interrupt-флаг должен быть установлен перед очисткой")
                    .isTrue();

            assertThat(currentThread.isInterrupted())
                    .as("Interrupt-флаг должен быть очищен после теста")
                    .isFalse();
        }
    }

    @Test
    void disabledRetryMeansExactlyOneAttempt() {
        AiProviderRetryExecutor executor =
                new AiProviderRetryExecutor(
                        new AiRetryProperties(
                                false,
                                1,
                                RETRY_BACKOFF,
                                RETRY_BACKOFF,
                                MAX_RETRY_AFTER,
                                Duration.ofSeconds(1)
                        )
                );

        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(
                () -> executor.execute(
                        PROVIDER,
                        MODEL,
                        OPERATION_ID,
                        Duration.ZERO,
                        context -> {
                            attempts.incrementAndGet();
                            throw rateLimit();
                        }
                )
        )
                .isInstanceOf(
                        AiProviderRateLimitedException.class
                );

        assertThat(attempts)
                .hasValue(1);
    }

    private static AiProviderRetryExecutor retryExecutor(
            int maxAttempts
    ) {
        return new AiProviderRetryExecutor(
                new AiRetryProperties(
                        true,
                        maxAttempts,
                        RETRY_BACKOFF,
                        RETRY_BACKOFF,
                        MAX_RETRY_AFTER,
                        TOTAL_TIMEOUT
                )
        );
    }

    private static AiProviderRateLimitedException rateLimit() {
        return new AiProviderRateLimitedException(
                PROVIDER,
                MODEL,
                429,
                "request-id",
                Duration.ZERO,
                true,
                "rate limited",
                null
        );
    }

    private static AiProviderRateLimitedException rateLimitWithDelay() {
    return new AiProviderRateLimitedException(
            PROVIDER,
            MODEL,
            429,
            "request-id",
            RETRY_BACKOFF,
            true,
            "rate limited",
            null
    );
}
}