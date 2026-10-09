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
import ru.safeai.gateway.ai.execution.AiExecutionAttemptObserver;
import ru.safeai.gateway.ai.execution.AiExecutionAttemptScope;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;
import ru.safeai.gateway.ai.provider.AiProviderRetryExecutor;
import ru.safeai.gateway.ai.provider.AiRetryProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
    void executeOutsideGovernedScopeIsRejected() {
        AiExecutionAttemptScope attemptScope =
                new AiExecutionAttemptScope();

        AiProviderRetryExecutor executor =
                new AiProviderRetryExecutor(
                        retryProperties(
                                2
                        ),
                        attemptScope
                );

        assertThatThrownBy(() ->
                executor.execute(
                        PROVIDER,
                        MODEL,
                        OPERATION_ID,
                        Duration.ZERO,
                        ignored -> freeResponse()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "No governed AI execution scope is active"
                );
    }

    @Test
    void connectFailureWithoutPreSubmissionEvidenceIsNotRetried() {
        RetryHarness harness =
                retryHarness(
                        2
                );

        AtomicInteger attempts =
                new AtomicInteger();

        AiProviderUnavailableException connectFailure =
                new AiProviderUnavailableException(
                        PROVIDER,
                        MODEL,
                        true,
                        false,
                        "connect failure",
                        null
                );

        /*
         * The adapter has no proof that zero request bytes reached the
         * provider. Legacy retry flags therefore cannot override
         * conservative outcome certainty.
         */
        assertThat(
                connectFailure.isRetryable()
        ).isTrue();

        assertThatThrownBy(() ->
                harness.execute(
                        Duration.ZERO,
                        context -> {
                            attempts.incrementAndGet();
                            throw connectFailure;
                        }
                )
        ).isSameAs(
                connectFailure
        );

        assertThat(
                attempts
        ).hasValue(
                1
        );
    }

    @Test
    void ambiguousReadTimeoutIsNotRetried() {
        RetryHarness harness =
                retryHarness(
                        3
                );

        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(() ->
                harness.execute(
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
        ).isInstanceOf(
                AiProviderTimeoutException.class
        );

        assertThat(
                attempts
        ).hasValue(
                1
        );
    }

    @Test
    void ambiguousNetworkOutcomeIsNotRetried() {
        RetryHarness harness =
                retryHarness(
                        3
                );

        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(() ->
                harness.execute(
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
        ).isInstanceOf(
                AiProviderUnavailableException.class
        );

        assertThat(
                attempts
        ).hasValue(
                1
        );
    }

    @Test
    void eachAttemptHasUniqueIdAndLogicalOperationIsStable() {
        RetryHarness harness =
                retryHarness(
                        2
                );

        List<AiProviderAttemptContext> contexts =
                new ArrayList<>();

        AiChatResponse result =
                harness.execute(
                        Duration.ZERO,
                        context -> {
                            contexts.add(
                                    context
                            );

                            if (context.attemptNumber() == 1) {
                                throw rateLimit();
                            }

                            return freeResponse();
                        }
                );

        assertThat(
                result.content()
        ).isEqualTo(
                "Ответ"
        );

        assertThat(
                contexts
        ).hasSize(
                2
        );

        assertThat(
                contexts
        )
                .extracting(
                        AiProviderAttemptContext::operationId
                )
                .containsOnly(
                        OPERATION_ID
                );

        assertThat(
                contexts
        )
                .extracting(
                        AiProviderAttemptContext::attemptNumber
                )
                .containsExactly(
                        1,
                        2
                );

        assertThat(
                contexts
        )
                .extracting(
                        AiProviderAttemptContext::attemptId
                )
                .doesNotHaveDuplicates();

        assertThat(
                contexts.get(
                        0
                ).attemptId()
        ).isNotEqualTo(
                contexts.get(
                        1
                ).attemptId()
        );
    }

    @Test
    void exhaustedQuotaIsNeverRetried() {
        RetryHarness harness =
                retryHarness(
                        3
                );

        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(() ->
                harness.execute(
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
        ).isInstanceOf(
                AiProviderQuotaExceededException.class
        );

        assertThat(
                attempts
        ).hasValue(
                1
        );
    }

    @Test
    void retryDoesNotStartAttemptThatCannotFitTotalDeadline() {
        RetryHarness harness =
                retryHarness(
                        new AiRetryProperties(
                                true,
                                3,
                                RETRY_BACKOFF,
                                RETRY_BACKOFF,
                                MAX_RETRY_AFTER,
                                Duration.ofSeconds(
                                        1
                                )
                        )
                );

        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(() ->
                harness.execute(
                        Duration.ofMillis(
                                900
                        ),
                        context -> {
                            attempts.incrementAndGet();

                            throw rateLimitWithRetryAfter(
                                    Duration.ofMillis(
                                            200
                                    )
                            );
                        }
                )
        ).isInstanceOf(
                AiProviderRateLimitedException.class
        );

        /*
         * Первый attempt допустим: 900 ms <= totalTimeout 1 s.
         *
         * Но retry потребовал бы ещё:
         * 200 ms Retry-After + 900 ms attempt timeout = 1.1 s.
         *
         * Поэтому executor обязан вернуть исходную provider exception
         * и не начинать второй physical attempt.
         */
        assertThat(
                attempts
        ).hasValue(
                1
        );
    }

    @Test
    void attemptTimeoutLongerThanTotalTimeoutFailsFast() {
        RetryHarness harness =
                retryHarness(
                        new AiRetryProperties(
                                true,
                                3,
                                RETRY_BACKOFF,
                                RETRY_BACKOFF,
                                MAX_RETRY_AFTER,
                                Duration.ofSeconds(
                                        1
                                )
                        )
                );

        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(() ->
                harness.execute(
                        Duration.ofSeconds(
                                2
                        ),
                        context -> {
                            attempts.incrementAndGet();
                            return freeResponse();
                        }
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "attempt timeout PT2S"
                )
                .hasMessageContaining(
                        "total-timeout PT1S"
                );

        /*
         * Timeout validation must happen before the physical provider call.
         */
        assertThat(
                attempts
        ).hasValue(
                0
        );
    }

    @Test
    void interruptedRetryRestoresInterruptFlag() {
        RetryHarness harness =
                retryHarness(
                        2
                );

        Thread currentThread =
                Thread.currentThread();

        currentThread.interrupt();

        try {
            assertThatThrownBy(() ->
                    harness.execute(
                            Duration.ZERO,
                            context -> {
                                throw rateLimitWithDelay();
                            }
                    )
            )
                    .isInstanceOf(
                            AiProviderException.class
                    )
                    .hasMessageContaining(
                            "interrupted"
                    );

            assertThat(
                    currentThread.isInterrupted()
            ).isTrue();
        } finally {
            boolean wasInterrupted =
                    Thread.interrupted();

            assertThat(
                    wasInterrupted
            )
                    .as(
                            "Interrupt-флаг должен быть установлен перед очисткой"
                    )
                    .isTrue();

            assertThat(
                    currentThread.isInterrupted()
            )
                    .as(
                            "Interrupt-флаг должен быть очищен после теста"
                    )
                    .isFalse();
        }
    }

    @Test
    void disabledRetryMeansExactlyOneAttempt() {
        RetryHarness harness =
                retryHarness(
                        new AiRetryProperties(
                                false,
                                1,
                                RETRY_BACKOFF,
                                RETRY_BACKOFF,
                                MAX_RETRY_AFTER,
                                Duration.ofSeconds(
                                        1
                                )
                        )
                );

        AtomicInteger attempts =
                new AtomicInteger();

        assertThatThrownBy(() ->
                harness.execute(
                        Duration.ZERO,
                        context -> {
                            attempts.incrementAndGet();
                            throw rateLimit();
                        }
                )
        ).isInstanceOf(
                AiProviderRateLimitedException.class
        );

        assertThat(
                attempts
        ).hasValue(
                1
        );
    }

    private static RetryHarness retryHarness(
            int maxAttempts
    ) {
        return retryHarness(
                retryProperties(
                        maxAttempts
                )
        );
    }

    private static RetryHarness retryHarness(
            AiRetryProperties properties
    ) {
        AiExecutionAttemptScope attemptScope =
                new AiExecutionAttemptScope();

        AiExecutionAttemptObserver observer =
                mock(
                        AiExecutionAttemptObserver.class
                );

        AiProviderRetryExecutor executor =
                new AiProviderRetryExecutor(
                        properties,
                        attemptScope
                );

        return new RetryHarness(
                attemptScope,
                observer,
                executor
        );
    }

    private static AiRetryProperties retryProperties(
            int maxAttempts
    ) {
        return new AiRetryProperties(
                true,
                maxAttempts,
                RETRY_BACKOFF,
                RETRY_BACKOFF,
                MAX_RETRY_AFTER,
                TOTAL_TIMEOUT
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
        return rateLimitWithRetryAfter(
                RETRY_BACKOFF
        );
    }

    private static AiProviderRateLimitedException rateLimitWithRetryAfter(
            Duration retryAfter
    ) {
        return new AiProviderRateLimitedException(
                PROVIDER,
                MODEL,
                429,
                "request-id",
                retryAfter,
                true,
                "rate limited",
                null
        );
    }

    private record RetryHarness(
            AiExecutionAttemptScope attemptScope,
            AiExecutionAttemptObserver observer,
            AiProviderRetryExecutor executor
    ) {

        private AiChatResponse execute(
                Duration attemptTimeout,
                Function<
                        AiProviderAttemptContext,
                        AiChatResponse
                        > action
        ) {
            return attemptScope.execute(
                    OPERATION_ID,
                    observer,
                    () -> executor.execute(
                            PROVIDER,
                            MODEL,
                            OPERATION_ID,
                            attemptTimeout,
                            action
                    )
            );
        }
    }
}
