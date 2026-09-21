package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.AiTokenUsage;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.pricing.PricingResult;
import java.math.BigDecimal;
import ru.safeai.gateway.ai.exception.AiProviderException;
import ru.safeai.gateway.ai.exception.AiProviderTimeoutException;
import ru.safeai.gateway.ai.exception.AiProviderUnavailableException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelExecutionAttemptEntityTest {
    private static final Instant STARTED_AT = Instant.parse("2026-09-13T12:00:00Z");

    @Test
    void genericConnectFailureDoesNotProveNoProviderExecution() {
        ModelExecutionAttemptEntity attempt = started();
        attempt.failed(new AiProviderUnavailableException(
                "openai", "gpt-5", true, false, "connect failure", null),
                STARTED_AT.plusSeconds(1));
        assertThat(attempt.getOutcome()).isEqualTo(ExecutionAttemptOutcome.AMBIGUOUS);
        assertThat(attempt.getOutcomeCertainty()).isEqualTo(OutcomeCertainty.AMBIGUOUS);
        assertThat(attempt.getRetrySafety()).isEqualTo(RetrySafety.SAME_TARGET_RETRY_FORBIDDEN);
        assertThat(attempt.getFallbackSafety()).isEqualTo(FallbackSafety.FALLBACK_FORBIDDEN);
    }

    @Test
    void explicitHttpRejectionIsKnownRejectedAndFallbackForbidden() {
        ModelExecutionAttemptEntity attempt = started();
        attempt.failed(new AiProviderException(
                "openai", "gpt-5", 400, "provider-request-id",
                AiProviderErrorType.INVALID_REQUEST, false, false,
                null, "invalid request", null), STARTED_AT.plusSeconds(1));
        assertThat(attempt.getOutcome()).isEqualTo(ExecutionAttemptOutcome.FAILED);
        assertThat(attempt.getOutcomeCertainty()).isEqualTo(OutcomeCertainty.KNOWN_REJECTED);
        assertThat(attempt.getRetrySafety()).isEqualTo(RetrySafety.SAME_TARGET_RETRY_FORBIDDEN);
        assertThat(attempt.getFallbackSafety()).isEqualTo(FallbackSafety.FALLBACK_FORBIDDEN);
    }

    @Test
    void ambiguousTimeoutForbidsRetryAndFallback() {
        ModelExecutionAttemptEntity attempt = started();
        attempt.failed(new AiProviderTimeoutException(
                "openai", "gpt-5", true, "read timeout", null),
                STARTED_AT.plusSeconds(1));
        assertThat(attempt.getOutcome()).isEqualTo(ExecutionAttemptOutcome.AMBIGUOUS);
        assertThat(attempt.getOutcomeCertainty()).isEqualTo(OutcomeCertainty.AMBIGUOUS);
        assertThat(attempt.getRetrySafety()).isEqualTo(RetrySafety.SAME_TARGET_RETRY_FORBIDDEN);
        assertThat(attempt.getFallbackSafety()).isEqualTo(FallbackSafety.FALLBACK_FORBIDDEN);
    }

    @Test
    void physicalSuccessPersistsActualResolvedModelUsageCacheAndVersionedCost() {
        ModelExecutionAttemptEntity attempt = started();
        AiChatResponse response = AiChatResponse.fromProvider(
                "answer", "gpt-5", "gpt-5-2026-09", "message-id", "request-id",
                AiResponseStatus.COMPLETED, "stop",
                new AiTokenUsage(20_000, 15_000, 2_000, 1_000, true, true),
                new PricingResult(PricingStatus.PRICED, new BigDecimal("0.029500000000"),
                        "USD", "cat-v1-example", STARTED_AT));
        attempt.succeeded(response, STARTED_AT.plusSeconds(1));
        assertThat(attempt.getRequestedPhysicalModel()).isEqualTo("gpt-5");
        assertThat(attempt.getResolvedPhysicalModel()).isEqualTo("gpt-5-2026-09");
        assertThat(attempt.getProviderRequestId()).isEqualTo("request-id");
        assertThat(attempt.getCachedInputTokens()).isEqualTo(15_000);
        assertThat(attempt.getCacheWriteInputTokens()).isEqualTo(2_000);
        assertThat(attempt.getCostUsd()).isEqualByComparingTo("0.029500000000");
        assertThat(attempt.getPriceBookVersion()).isEqualTo("cat-v1-example");
        assertThat(attempt.getOutcomeCertainty()).isEqualTo(OutcomeCertainty.KNOWN_EXECUTED);
        assertThatThrownBy(() -> attempt.ambiguous("after-commit", STARTED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void successfulHttpRejectionMayAuthorizeSameTargetRetryButNeverFallback() {
        ModelExecutionAttemptEntity attempt = started();
        attempt.failed(new AiProviderException("openai", "gpt-5", 429,
                "request-id", AiProviderErrorType.RATE_LIMITED,
                true, false, null, "rate limited", null), STARTED_AT.plusSeconds(1));
        assertThat(attempt.getOutcome()).isEqualTo(ExecutionAttemptOutcome.FAILED);
        assertThat(attempt.getOutcomeCertainty()).isEqualTo(OutcomeCertainty.KNOWN_REJECTED);
        assertThat(attempt.getRetrySafety()).isEqualTo(RetrySafety.SAME_TARGET_RETRY_ALLOWED);
        assertThat(attempt.getFallbackSafety()).isEqualTo(FallbackSafety.FALLBACK_FORBIDDEN);
    }

    @Test
    void terminalTimestampBeforeStartCannotCorruptStartedEvidence() {
        ModelExecutionAttemptEntity attempt = started();
        assertThatThrownBy(() -> attempt.ambiguous("timeout", STARTED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempt.getOutcome()).isEqualTo(ExecutionAttemptOutcome.STARTED);
        assertThat(attempt.getFinishedAt()).isNull();
    }

    private static ModelExecutionAttemptEntity started() {
        return ModelExecutionAttemptEntity.started(UUID.randomUUID(), UUID.randomUUID(),
                1, ProviderExecutionTarget.staticTarget("openai", "gpt-5"), STARTED_AT);
    }
}
