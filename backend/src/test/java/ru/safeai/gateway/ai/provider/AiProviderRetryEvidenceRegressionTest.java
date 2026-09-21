package ru.safeai.gateway.ai.provider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderQuotaExceededException;
import ru.safeai.gateway.ai.exception.AiProviderRateLimitedException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;
import ru.safeai.gateway.ai.execution.OutcomeCertainty;
import ru.safeai.gateway.ai.execution.ProviderFailureCertainty;
import ru.safeai.gateway.ai.testsupport.AiTestFixtures;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Supplements existing retry tests and the V54 execution-ledger tests. */
@Tag("unit")
@Timeout(5)
class AiProviderRetryEvidenceRegressionTest {
    private static final UUID OPERATION = AiTestFixtures.OPERATION_ID;

    @Test
    void explicitRateLimitCanRetrySameTargetWithDistinctPhysicalIds() {
        List<AiProviderAttemptContext> seen = new ArrayList<>();
        AiChatResponse expected = AiTestFixtures.freeResponse();
        AiChatResponse actual = retry(2).execute("openai", "gpt-4.1",
                OPERATION, Duration.ZERO, context -> {
                    seen.add(context);
                    if (seen.size() == 1) throw rateLimit();
                    return expected;
                });
        assertThat(actual).isSameAs(expected);
        assertThat(seen).hasSize(2);
        assertThat(seen).extracting(AiProviderAttemptContext::operationId)
                .containsOnly(OPERATION);
        assertThat(seen).extracting(AiProviderAttemptContext::attemptNumber)
                .containsExactly(1, 2);
        assertThat(seen).extracting(AiProviderAttemptContext::attemptId)
                .doesNotHaveDuplicates();
    }

    @Test
    void genericConnectExceptionCannotProveSafeRetryEvenWithLegacyFalseAmbiguityFlag() {
        AtomicInteger attempts = new AtomicInteger();
        AiProviderUnavailableException error = new AiProviderUnavailableException(
                "openai", "gpt-4.1", true, false, "connect status unproved", null);
        assertThat(ProviderFailureCertainty.classify(error))
                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
        assertThatThrownBy(() -> retry(3).execute("openai", "gpt-4.1",
                OPERATION, Duration.ZERO, ctx -> {
                    attempts.incrementAndGet();
                    throw error;
                })).isSameAs(error);
        assertThat(attempts).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unproved503NeverRetriesRegardlessOfLegacyRecommendedFlag(boolean retryable) {
        AtomicInteger attempts = new AtomicInteger();
        AiProviderException error = providerError(503, AiProviderErrorType.SERVER_ERROR, retryable);
        assertThat(ProviderFailureCertainty.classify(error))
                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
        assertThatThrownBy(() -> retry(3).execute("openai", "gpt-4.1",
                OPERATION, Duration.ZERO, ctx -> {
                    attempts.incrementAndGet();
                    throw error;
                })).isSameAs(error);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void timeout408NeverRetriesWithoutProofProviderDidNotExecute() {
        AtomicInteger attempts = new AtomicInteger();
        AiProviderException error = providerError(408, AiProviderErrorType.TIMEOUT, true);
        assertThat(ProviderFailureCertainty.classify(error))
                .isEqualTo(OutcomeCertainty.AMBIGUOUS);
        assertThatThrownBy(() -> retry(3).execute("openai", "gpt-4.1",
                OPERATION, Duration.ZERO, ctx -> {
                    attempts.incrementAndGet();
                    throw error;
                })).isSameAs(error);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void quotaIsKnownRejectedButStillNotRetryable() {
        AtomicInteger attempts = new AtomicInteger();
        AiProviderQuotaExceededException error = new AiProviderQuotaExceededException(
                "openai", "gpt-4.1", 429, "request-id",
                "insufficient_quota", "quota exhausted", null);
        assertThat(ProviderFailureCertainty.classify(error))
                .isEqualTo(OutcomeCertainty.KNOWN_REJECTED);
        assertThatThrownBy(() -> retry(3).execute("openai", "gpt-4.1",
                OPERATION, Duration.ZERO, ctx -> {
                    attempts.incrementAndGet();
                    throw error;
                })).isSameAs(error);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void identityAndDeadlineRejectBeforeAnyPhysicalAction() {
        AtomicInteger actions = new AtomicInteger();
        assertThatThrownBy(() -> retry(2).execute("openai", "gpt-4.1", null,
                Duration.ZERO, ctx -> {
                    actions.incrementAndGet();
                    return AiTestFixtures.freeResponse();
                })).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> retry(2).execute("openai", "gpt-4.1", OPERATION,
                Duration.ofSeconds(3), ctx -> {
                    actions.incrementAndGet();
                    return AiTestFixtures.freeResponse();
                })).isInstanceOf(IllegalStateException.class);
        assertThat(actions).hasValue(0);
    }

    private static AiProviderRetryExecutor retry(int maximum) {
        return new AiProviderRetryExecutor(new AiRetryProperties(true, maximum,
                Duration.ofMillis(10), Duration.ofMillis(10),
                Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private static AiProviderRateLimitedException rateLimit() {
        return new AiProviderRateLimitedException("openai", "gpt-4.1", 429,
                "provider-request-id", Duration.ZERO, true, "rate limit", null);
    }

    private static AiProviderException providerError(int status,
                                                     AiProviderErrorType type,
                                                     boolean retryable) {
        return new AiProviderException("openai", "gpt-4.1", status,
                "provider-request-id", type, retryable, false,
                null, "provider failure", null);
    }
}
